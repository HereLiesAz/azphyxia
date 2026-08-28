# Branding

## Logo

illumera's mark is a folded play-triangle: a pink-to-orange gradient
triangle whose pointed tip curls back on itself, showing a darker maroon
underside — like a ribbon or a dog-eared page corner folded at the point.

| File | Background | Use |
|------|------------|-----|
| [`illumera.png`](illumera.png) | Transparent | Docs, light-background contexts, README embeds |
| [`illumera2.png`](illumera2.png) | Black | App icon, banners, dark-background contexts |

Both are 512×512 master artwork. Treat them as the source of truth for the
mark — derived assets (launcher icons, banners, in-app drawables) should be
re-cropped/re-scaled from these, not redrawn by hand.

### Color palette

Pulled directly from the logo's gradient, and reused as the `Illumera`
built-in theme (see [`THEMING.md`](THEMING.md)):

| Role | Hex |
|------|-----|
| Pink (gradient start) | `#FF2E7A` |
| Mid-tone | `#FA3E58` |
| Orange (gradient end) | `#FF7A1E` |
| Fold underside (dark maroon) | `#8A1530`–`#B0203E` |

### Where the logo appears in the app

| Location | Resource |
|----------|----------|
| App launcher icon (adaptive) | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` → `drawable-nodpi/ic_launcher_foreground.png` (from `illumera.png`) + `drawable/ic_launcher_background.xml` (solid black vector) + `drawable-nodpi/ic_launcher_monochrome.png` (white alpha-mask silhouette, for themed icons — solid white in the shape, fully transparent outside, so it looks blank against a white preview background by design) |
| Legacy launcher icon (pre-adaptive-icon fallback, unused at runtime since `minSdk 26`) | `app/src/main/res/mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png` (scaled from `illumera2.png`) |
| Android TV launcher banner | `app/src/main/res/drawable-xxhdpi/banner.png` (320×180, real logo + "ILLUMERA" wordmark on black) |
| In-app logo (splash screen, settings) | `app/src/main/res/drawable-nodpi/logo_illumera.png` (tightly cropped from `illumera.png`), referenced from `MainActivity.kt` and `ui/settings/SettingsSubScreens.kt` as `R.drawable.logo_illumera` |
| README header | `screenshots/banner.png` (real logo + "ILLUMERA" wordmark on white) |

### Updating the logo

All in-app/launcher assets above are PNGs derived directly from
`illumera.png`/`illumera2.png` — there is no hand-traced vector recreation
anymore (an earlier hand-drawn `<vector>` approximation was replaced with
crops/scales of the real master artwork). If the mark ever changes, replace
the two master PNGs in this folder and regenerate every derived asset in
the table above from them — don't patch individual derived files by hand,
or they'll drift out of sync with each other (this happened once already:
`screenshots/banner.png` kept a pre-rebrand "LUMERA" wordmark long after the
rest of the app moved to "illumera").

The adaptive icon foreground is a straight scale of `illumera.png` to
432×432 — the master art already has generous padding around the triangle
(content occupies roughly the center 41% of the 512×512 canvas), which
comfortably clears the adaptive icon's safe zone (a circle 66dp in diameter
centered in the 108×108dp canvas) without any extra insetting. The
monochrome layer is the same artwork's alpha channel filled solid white.

## Naming

The product name is **illumera** (lowercase in running text and the
`app_name` string resource; the launcher banner and README wordmark render
it as "ILLUMERA" in caps for visual weight). The Android package/application
ID is `com.hereliesaz.illumera`.

illumera is a rebrand of an earlier project called **Lumera**. That history
still shows up in the codebase as internal-only Kotlin identifiers —
`LumeraCard`, `LumeraTheme`, `LumeraBackground`, `LumeraApplication`, the
`DefaultThemes` doc comment, log tags like `"LumeraTorrent"` — none of which
are user-visible. There's no functional reason to rename them; do so only
as part of a deliberate, low-risk cleanup pass, not incidentally while
touching nearby code.
