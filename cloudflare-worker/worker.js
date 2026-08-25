/**
 * illumera Crash Report Worker
 *
 * Receives ACRA crash reports via HTTP POST from the app and relays them
 * as GitHub issues on this repo — no GitHub sign-in required on-device,
 * since only this worker (not the app or its users) holds the GitHub
 * token. Optionally also emails the report via Resend, if configured.
 *
 * Crash reports for the same underlying bug are deduplicated: a short
 * hash of the exception type + top stack frames becomes a `crash-<hash>`
 * label. A new occurrence of a known crash adds a comment to the existing
 * open issue instead of opening a duplicate; an unrecognized signature
 * opens a new issue.
 *
 * Setup:
 * 1. Create a GitHub fine-grained personal access token scoped to this
 *    repo only, with "Issues: Read and write" permission — nothing else.
 *    https://github.com/settings/personal-access-tokens/new
 * 2. Deploy this worker: npx wrangler deploy
 * 3. Set the secrets/vars:
 *      npx wrangler secret put GITHUB_TOKEN
 *      npx wrangler secret put AUTH_TOKEN        # shared secret the app sends
 *    Edit wrangler.toml's [vars] to point GITHUB_OWNER/GITHUB_REPO at your repo
 *    (defaults to HereLiesAz/illumera).
 * 4. Optional email relay via Resend (https://resend.com — free tier):
 *      npx wrangler secret put RESEND_API_KEY
 *      npx wrangler secret put REPORT_EMAIL
 * 5. Put the worker's URL + AUTH_TOKEN in local.properties (local dev) or
 *    the ACRA_URL / ACRA_TOKEN Actions secrets (CI release builds) — see
 *    ../ci/README.md.
 */

const GITHUB_API = "https://api.github.com";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/crash-report") {
      return new Response("Not found", { status: 404 });
    }

    // Basic auth check to prevent spam (ACRA sends Basic auth)
    const authHeader = request.headers.get("Authorization") || "";
    if (env.AUTH_TOKEN) {
      const expected = "Basic " + btoa("acra:" + env.AUTH_TOKEN);
      if (authHeader !== expected) {
        return new Response("Unauthorized", { status: 401 });
      }
    }

    let report;
    try {
      report = await request.json();
    } catch (e) {
      return new Response("Invalid JSON", { status: 400 });
    }

    const results = await Promise.allSettled([
      relayToGitHub(report, env),
      relayToEmail(report, env),
    ]);

    const anySucceeded = results.some((r) => r.status === "fulfilled" && r.value);
    for (const r of results) {
      if (r.status === "rejected") console.error("Relay failed:", r.reason);
    }

    return anySucceeded
      ? new Response("OK", { status: 200 })
      : new Response("All relays failed or unconfigured", { status: 500 });
  },
};

async function relayToGitHub(report, env) {
  if (!env.GITHUB_TOKEN || !env.GITHUB_OWNER || !env.GITHUB_REPO) return false;

  const stackTrace = report.STACK_TRACE || "No stack trace";
  const appVersion = report.APP_VERSION_NAME || "unknown";
  const androidVersion = report.ANDROID_VERSION || "unknown";
  const phoneModel = report.PHONE_MODEL || "unknown";
  const brand = report.BRAND || "unknown";
  const crashDate = report.USER_CRASH_DATE || "unknown";
  const totalMemory = report.TOTAL_MEM_SIZE || "unknown";
  const availableMemory = report.AVAILABLE_MEM_SIZE || "unknown";

  const signature = await crashSignature(stackTrace);
  const dedupeLabel = `crash-${signature}`;
  const exceptionLine = stackTrace.split("\n")[0]?.trim() || "Unknown exception";

  const occurrence = [
    `**Occurrence** — ${crashDate}`,
    `- App version: \`${appVersion}\``,
    `- Device: ${brand} ${phoneModel}, Android ${androidVersion}`,
    `- Memory: ${availableMemory} available / ${totalMemory} total`,
    "",
    "<details><summary>Stack trace</summary>",
    "",
    "```",
    stackTrace,
    "```",
    "</details>",
  ].join("\n");

  const ghHeaders = {
    Authorization: `Bearer ${env.GITHUB_TOKEN}`,
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "illumera-crash-worker",
    "Content-Type": "application/json",
  };
  const repoPath = `${env.GITHUB_OWNER}/${env.GITHUB_REPO}`;

  // Look for an existing open issue with this crash's signature label.
  const searchQuery = encodeURIComponent(
    `repo:${repoPath} label:${dedupeLabel} state:open`
  );
  const searchResponse = await fetch(
    `${GITHUB_API}/search/issues?q=${searchQuery}`,
    { headers: ghHeaders }
  );
  if (!searchResponse.ok) {
    console.error("GitHub search failed:", await searchResponse.text());
    return false;
  }
  const searchResult = await searchResponse.json();
  const existingIssue = searchResult.items?.[0];

  if (existingIssue) {
    const commentResponse = await fetch(
      `${GITHUB_API}/repos/${repoPath}/issues/${existingIssue.number}/comments`,
      {
        method: "POST",
        headers: ghHeaders,
        body: JSON.stringify({ body: occurrence }),
      }
    );
    if (!commentResponse.ok) {
      console.error("GitHub comment failed:", await commentResponse.text());
      return false;
    }
    return true;
  }

  const title = `Crash: ${exceptionLine}`.slice(0, 250);
  const body = [
    `Automatically reported crash from v${appVersion} (${brand} ${phoneModel}, Android ${androidVersion}).`,
    "",
    occurrence,
  ].join("\n");

  const createResponse = await fetch(`${GITHUB_API}/repos/${repoPath}/issues`, {
    method: "POST",
    headers: ghHeaders,
    body: JSON.stringify({
      title,
      body,
      labels: ["crash-report", dedupeLabel],
    }),
  });
  if (!createResponse.ok) {
    // Labels that don't exist yet can occasionally be rejected depending on
    // token permissions — retry once without labels rather than lose the report.
    const retryResponse = await fetch(`${GITHUB_API}/repos/${repoPath}/issues`, {
      method: "POST",
      headers: ghHeaders,
      body: JSON.stringify({ title, body }),
    });
    if (!retryResponse.ok) {
      console.error("GitHub issue creation failed:", await retryResponse.text());
      return false;
    }
  }
  return true;
}

async function relayToEmail(report, env) {
  if (!env.RESEND_API_KEY || !env.REPORT_EMAIL) return false;

  const stackTrace = report.STACK_TRACE || "No stack trace";
  const appVersion = report.APP_VERSION_NAME || "unknown";
  const androidVersion = report.ANDROID_VERSION || "unknown";
  const phoneModel = report.PHONE_MODEL || "unknown";
  const brand = report.BRAND || "unknown";
  const crashDate = report.USER_CRASH_DATE || "unknown";
  const totalMemory = report.TOTAL_MEM_SIZE || "unknown";
  const availableMemory = report.AVAILABLE_MEM_SIZE || "unknown";
  const display = report.DISPLAY || "unknown";

  const subject = `illumera Crash - v${appVersion} - ${brand} ${phoneModel}`;
  const body = `
ILLUMERA CRASH REPORT
======================

Date: ${crashDate}
App Version: ${appVersion}

DEVICE INFO
-----------
Brand: ${brand}
Model: ${phoneModel}
Android: ${androidVersion}
Display: ${display}
Total Memory: ${totalMemory}
Available Memory: ${availableMemory}

STACK TRACE
-----------
${stackTrace}

FULL REPORT (JSON)
-------------------
${JSON.stringify(report, null, 2)}
`.trim();

  const emailResponse = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.RESEND_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from: "illumera Crashes <crashes@resend.dev>",
      to: [env.REPORT_EMAIL],
      subject,
      text: body,
    }),
  });

  if (!emailResponse.ok) {
    console.error("Resend error:", await emailResponse.text());
    return false;
  }
  return true;
}

/** Short, stable signature for a crash: hash of the exception type + top frames. */
async function crashSignature(stackTrace) {
  // Drop line numbers/memory addresses so the same bug at the same call site
  // still hashes identically even if minor formatting differs between reports.
  const normalized = stackTrace
    .split("\n")
    .slice(0, 6)
    .map((line) => line.replace(/:\d+\)?$/, "").trim())
    .join("\n");

  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(normalized)
  );
  const hex = [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return hex.slice(0, 12);
}
