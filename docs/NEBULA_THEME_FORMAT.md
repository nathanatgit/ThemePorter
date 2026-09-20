# NebulaAIOS theme format (.zmtp)

This document maps the theme structure the app builds. The evidence comes from:

- the stock themes in `stockThemes/`: `default_theme_04`, `13`, `49` and `60`, all Theme Version 16.0.1;
- the community ports in `highqualityPortedTheme/`, which are known to apply on a Nubia NX733J;
- strings in `com.zte.beautify` (the Themes app) and `com.zte.mifavor.launcher`, pulled from the same device.

Values marked **inferred** are not observed directly and still need checking on a device.

**Current scope:** the app itself now only *builds* fixed-shape themes (section 4.2's right column) and never switches icon shape live. Sections 4–5.1 documenting the variable-shape format are kept as reference for how the stock `04`/`13`/`49` themes and the system itself work, not as a description of the app's current output. The former variable-shape generator and root-based shape switcher are archived on the `variableIcon` git branch.

## 1. Outer archive

A `.zmtp` is a plain ZIP. Every stock theme uses DEFLATE for every entry, has no directory entries, and lists entries in alphabetical order.

| Entry | Stock content |
|---|---|
| `description.xml` | Metadata (section 2) |
| `lockscreen/wallpaper1.jpg` | Lock-screen wallpaper, 1216×2688. The Themes app also accepts `.png` and `wallpaper169.*`. |
| `overlays/androidzte/res/values/{colors,config}.xml` | Raw values; only in `04` and in community ports |
| `overlays/androidzte/resources.apk` | Framework overlay (section 3) |
| `overlays/com.android.settings/resources.apk` | Settings overlay |
| `overlays/com.android.systemui/resources.apk` | SystemUI overlay |
| `overlays/com.zte.mifavor.launcher.resource/raw/icons_cur.zip` | All launcher assets (section 4) |
| `preview/1.mp4` | Optional preview video |
| `preview/preview00.jpg` … `preview05.jpg` | 1080×2400 preview pictures |
| `wallpaper/wallpaper1.jpg` | Home wallpaper, 1216×2688 |

## 2. description.xml

```xml
<root>
  <item key="id" value="default_theme_13"/>            <!-- online themes: online_theme_<vendor>_<date>_<n> -->
  <item key="LockScreenWallpaperType" value="1"/>      <!-- 1 static, 2 ZTE lock screen, 3 dynamic, 5 pressure -->
  <item key="label-en" value="Classic"/>
  <item key="label-zh-rCN" value="经典"/>
  <item key="intro-en" value="…"/>
  <item key="intro-zh-rCN" value="…"/>
  <item key="author" value="MyOS"/>
  <item key="ischarge" value="0"/>
  <item key="price" value="0"/>
  <item key="classify-zh-rCN" value="其它"/>
  <item key="classify-en" value="Other"/>
  <item key="Theme Version" value="16.0.1"/>
  <item key="defaultIconShape" value="config_1"/>       <!-- variable-shape themes only -->
  <item key="recompiled" value="true"/>
  <item key="isSupportIconShapeChange" value="true"/>  <!-- variable-shape themes only -->
</root>
```

`defaultIconShape` names one of the five `config*.xml` files: `04` uses `config`, while `13` and `49` use `config_1`. `LockScreenWallpaperType` has nothing to do with icon shape (`04` and `13` use 1, `49` and `60` use 2).

## 3. Overlays

These are unsigned, resource-only APKs (`hasCode=false`, `<overlay targetPackage=… priority=1>`).

- `androidzte/resources.apk`, 651 bytes in `13`/`49`/`60`: package `androidzteoverLayZTE`, no resource table, a no-op. `04` instead ships `androidzteoverLayZTEShape`, carrying the `mfv_common_*` colors.
- `com.android.settings` and `com.android.systemui`: one drawable, `theme_shortcut_bg_settings`, in xhdpi, xxhdpi and xxxhdpi.
- Community ports copy the three APKs from `default_theme_13` byte for byte. The app does the same and bundles them in `app/src/main/assets/overlays/`.

## 4. icons_cur.zip

Every file lives under `icon/`. `13`, `49` and `60` begin with an `icon/` directory entry. PNGs are stored or deflated (the stock themes mix both), and XML is deflated.

### 4.1 App icon names

```
<package with . → _>-<activity class with . → _><suffix>.png
com_tencent_mm-com_tencent_mm_ui_LauncherUI_back.png
```

- The activity is the launcher activity's full class name. A few stock entries use the short name instead (`com_android_messaging-ui_conversationlist_ConversationListActivity`), so the app copies stock stems verbatim for system apps.
- **One app should answer to one name.** A package can be written under several names at once - the stock component stem, the activity the device really exposes, and the package-only name - and when they hold different pictures the one displayed is not the one intended. Stock themes name a system app with exactly one file, so `IconPlanner` reserves a package as soon as a system app is assigned to it and keeps the other writers off it. See "Confirmed on the device".
- **Package-only names** (`com_tencent_mm.png`) also work. Stock `60` ships `com_zte_nebulatranslation.png`, and the community ports ship between 500 and 6,500 such files, almost none with an activity.
- The Themes app draws its theme card from these stems (`NebulaSpec.PREVIEW_STEMS`): notepad, recorder, calculator, camera, Chrome, contacts/dialer, gallery, settings, Google Photos, WeChat, QQ, phone manager, clock and files.

### 4.2 Icon files per style

| | Variable shape (`04`, `13`, `49`) | Fixed shape (`60`) |
|---|---|---|
| Per app | `_back.png` + `_front.png`, both 216×216 | one flat `.png`, 156×156 |
| Theme-card stems | also `_square.png` (216) and a flat `.png` | — |
| Counts | `04`: 376 front / 373 back · `13`: 323 / 323 · `49`: 50 / 52 | `60`: 61 flat |

The layer anatomy was measured on WeChat, Notes and Settings in all three variable-shape themes:

- `_back` is fully opaque and full-bleed, with no shape of its own.
- `_front` is the glyph on transparency, with its bounding box inside roughly 50–170 px of the 216 px canvas.
- `_square` is `_back` with `_front` composited on top.
- The flat copy is `_square` clipped to the default shape. It is 216 px in `04` (a circle, 77% opaque) and `49` (a squircle, 95%), and 156 px in `13`.

### 4.3 Shapes

The five shapes use SVG paths in a 100×100 viewport, stored as `<string name="config_icon_zte">"…"</string>`:

| File | Suffix | Shape |
|---|---|---|
| `config.xml` | `` | circle `M50,0A50,50,0,1,1,0,50,50,50,0,0,1,50,0Z` |
| `config_1.xml` | `_1` | squircle (corner ≈ 25) |
| `config_2.xml` | `_2` | large-radius rounded square |
| `config_3.xml` | `_3` | leaf (two sharp corners) |
| `config_4.xml` | `_4` | teardrop |

Fixed-shape themes ship a single `config.xml`, which holds the squircle path in `60`. These five system shapes are no longer generated by the app (see the note in the intro); their paths live only in the stock themes and community ports now.

For fixed app icons, the porter rasterizes one of its own `FixedIconShape` outlines into the final PNG instead: `None` is the stock-compatible default and writes the imported artwork without a new outline or plate; the rest are Circle, Square, Samsung Squircle, iOS Squircle, Slanted, Fan, Pentagon, Gem, Sunny, 6/9/12-sided Cookie, 4/8-leaf Clover, Soft Burst, Flower, Ghostish, Pixel Circle and Heart (20 entries total, in `NebulaSpec.kt`). None of these correspond to the five system shapes above; they are plain PNG outlines and never create `config*.xml` files or make a theme shape-switchable after import.

When a fixed outline is selected, it is also applied to the fixed theme’s `theme_mask_icon`, folder artwork, dynamic calendar/clock strips, shortcut plate and unthemed-app background plates. The porter writes `theme_info.xml` with `overlapBg=1` for every selected fixed outline—not just sources that supply `iconback`—so an app absent from the pack is composited over that selected-shaped fallback plate. The one fixed `config.xml` remains a stock-compatible system config and is not used to express porter-only outlines. The app previews a selectable imported icon and exposes independent icon and background size sliders (25–125% and 35–125% of the 192 px output), except that Cover hides the ineffective icon-size slider because it always fills the shaped background. The selected preview icon never changes assignments or exported icon selection; its slider changes are coalesced and rendered away from the UI thread. Overlay exposes an icon-transparency-strength slider for its foreground/plate blend. The fixed palette groups collapsible Background tint and Icon tint controls with matching compact color swatches. The fixed pipeline colorizes imported artwork with multiply-style grayscale tinting, not a flat RGB overlay: each target channel is the icon’s grayscale value multiplied by the corresponding selected tint channel, while alpha remains intact; selected palette colors are tuned with direct HSV sliders. This cannot multiply-recolor an app absent from the pack because the launcher, rather than the theme archive, owns that app bitmap; its selected-shaped fallback plate still receives the background tint. The available draw modes are **Overlay** (a tinted shaped plate behind the icon), **Cover** (source center-cropped to fill the shaped background), and **Crop to background** (source keeps its selected scale and is clipped to the background outline). The final Custom PNG tile in the fixed-shape grid selects a square PNG (recommended at least 512×512 with centred content and edge bleed) to replace source `iconback` as Overlay’s shaped plate.

### 4.4 Launcher assets

Variable-shape themes ship each of the following five times, named `<base>_config<suffix>.png`. Fixed-shape themes ship each once, named `<base>.png`.

| Base | Size | Content |
|---|---|---|
| `theme_mask_icon` | 156×156 | the shape in solid black |
| `theme_folder_icon` | 216×216 (`04`), 156×156 (`13`) | the shape in white at alpha 153. Absent in `49` and `60`. |
| `theme_folder_add` | 156×156 | a dashed shape outline with a "+" |
| `theme_dynamic_calendar` | 4992×156 (`49`: 6656×208) | 32 frames: days 1–31, then the background |
| `theme_dynamic_clock` | 468×156 (`49`: 624×208) | 3 frames: hour hand, minute hand, dial |

The remaining files are shipped once:

| File | Notes |
|---|---|
| `theme_shortcut_bg_settings.png` | 216 px opaque square in variable-shape themes; 156 px, pre-shaped, in `60` |
| `theme_bg_icon-0_65.png`, `theme_bg_icon-66_100.png` | In every stock theme: a light and a dark plate for unthemed apps. Variable-shape themes use 216 px, opaque and unshaped (`04` white/#6C7887, `13` white/black, `49` #F3F3F3/#262627). `60` uses 156 px, pre-clipped to the squircle (#EBEDED/#282728). |
| `theme_info_dynamic_calendar.xml` | `showWeekInfo`, `paddingTop`, `textColor` (#AARRGGBB), `textSize` |
| `theme_info_icon.xml` | `<item packageName="…" className="…"/>` for each layered icon (374 of 375 in `04`). Present in `04` and `13`, absent in `49`, which still switches shapes, so it is optional. |
| `theme_info.xml` | Community fixed ports only: `<item key="overlapBg" value="1"/>`, which draws icons over `theme_bg_icon` |
| `theme_icon_effect_top.png` | Community fixed ports only: an overlay drawn on top of icons (MIUI `icon_border`) |

### 4.5 Dynamic calendar and clock

- **Calendar:** frames 0–30 hold only the day numbers, on transparency. Frame 31 is the background tile, already clipped to that variant's shape. The launcher draws background, then the day frame, then the weekday text (if `showWeekInfo=1`), styled by `theme_info_dynamic_calendar.xml`.
- **Clock:** hands are drawn from the center, pointing at 3 o'clock. The launcher's rotation origin is **inferred** from the artwork; the app rotates MIUI hands, which point at 12, by +90°.

The stock `theme_info_dynamic_calendar.xml` values are:

| Theme | showWeekInfo | paddingTop | textColor | textSize |
|---|---|---|---|---|
| 04 | 0 | 7 | #ffFFFFFF | 9 |
| 13 | 1 | 7 | #E5000000 | 9 |
| 49 | 0 | 6 | #ff3A3A3A | 9 |
| 60 | 1 | 6 | #ff0A7DFF | 9 |

## 5. What makes a theme variable-shape

Themes `04`, `13` and `49` have all of the following; `60` has none:

1. `defaultIconShape=config*` and `isSupportIconShapeChange=true` in `description.xml`.
2. `config.xml` through `config_4.xml`.
3. `_config`, `_config_1` … `_config_4` variants of the mask, folder add, calendar, clock and (when present) folder icon assets.
4. `_back`/`_front` layers for app icons, where the background is full-bleed and shapeless.
5. **A theme name starting with `default_theme_`.** Without it, the framework does not apply the selected mask to layered icons.

Even with all five, the Themes app shows no shape picker for an imported theme (see "Switching the shape" below). Nebula Theme Porter no longer builds this style or switches shape through root — see the note in the intro.

### 5.1 How the system applies a theme (from the NX733J's `services.jar` and `framework.jar`)

- **Theme name.** An imported theme's name is its **file name without extension**, not the description `id` or label. The system caches it under `/data/resource-cache/cache/<name>` (for example `one_ui图标包`, whose label is "One UI") and records it in `persist.sys.theme_icon_name` and `/data/resource-cache/theme_global_config/theme_icon_global_Settings.xml`.
- **Reading the description.** `com.android.server.ThemeService` reads only four keys from `description.xml`: `Theme Version`, `recompiled`, `isSupportIconShapeChange` and `defaultIconShape`. It stores the chosen shape in `persist.sys.icon_config_mask` (e.g. `config_2`) and `persist.sys.supprot_icon_change_shape`.
- **Unpacking and renaming.** `icons_cur.zip` is unzipped to `/data/resource-cache/cache/icon-cache/icon/icon`. `ThemeService.reNameIconForShapePic` then copies the `theme_mask_icon`, `theme_folder_icon`, `theme_folder_add`, `theme_dynamic_calendar` and `theme_dynamic_clock` variant for the selected config to the plain name.
- **Package-only fallback.** `IconPackHelper.reNameIconFile` renames every `pkg-cls[_back|_front].png` whose activity is not installed to `pkg[_back|_front].png`, skipping `theme_bg_icon-*` and `theme_icon_effect_top-*`. Package-only names are therefore an official fallback.
- **Building the icon.** `IconPackHelper.readShapeDrawable` loads `<pkg>-<cls>_back.png` and `_front.png` (falling back to `<pkg>_back/_front`). It wraps them in a **stock `AdaptiveIconDrawable`**, so each layer is drawn at 150% and only its middle 2/3 is visible. Artwork must sit inside that middle region: stock fronts span 43–58% of the 216 px layer, and stock backs are plain.
- **Applying the shape.** `AdaptiveIconDrawableMifavor.adaptiveIconDrawableMulthemeIcon` returns immediately unless the current icon theme name starts with `default_theme_`. Only then does it load the mask path from the selected `config*.xml`, falling back to `androidzte:string/config_icon_zte`.
- **Switching the shape.**
  - *Who gets the picker.* The Themes app (`com.zte.beautify`, `IconChoosePresenter.initData`) lists shapes only for themes flagged `isThemeSupportIconChange` in its preset list (`ThemeResource_MFV.apk` `res/mw.xml`), which names only `default_theme_04`, `13` and `49`. Imported themes never get the picker, whatever they contain.
  - *What the picker sends.* Choosing a shape sends the broadcast `com.zte.theme.ICON_SHAPE_CHANGE` (extras `CONFIG_NAME` = `config*`, `THIRD_PART` = boolean). ThemeService registers for it with the `signature|privileged` permission `androidzte.permission.ZTE_THEME_CHANGE`.
  - *No theme check.* `ThemeService.doApplyThemeIconChange` renames the variant assets, then `setCurrIconSetting` updates `persist.sys.icon_config_mask`. The launcher reloads its icons and its `MiFavor_current_icon` preference follows.
  - *Root works.* An `am broadcast` from uid 0 passes the permission check. Apps can read `persist.sys.icon_config_mask` and `persist.sys.supprot_icon_change_shape`, but not `persist.sys.theme_icon_name`.
- **Unthemed apps.** `IconPackHelper.translateToShapeIcon` composes the app icon with `theme_mask_icon.png` and the `theme_bg_icon-<min>_<max>.png` whose range contains the icon's brightness.

### 5.2 Conventions observed in other community-made themes

- **Calendar from 31 complete icons:** frames 1–31 hold the icons, and frame 32 repeats day 1.
- **Calendar from a backplate:** frames 1–31 are the backplate with a centred number, and frame 32 is the bare backplate.
- **Clock:** the hour hand, minute hand and dial are stitched left to right, with both hands rotated to 3 o'clock.
- **Icon pack tool:** it writes only package-only flat PNGs (`icon/<pkg>.png`), plus copies under the ~50 NebulaAIOS target aliases.

## 6. MIUI .mtz → Nebula

| MIUI | Nebula |
|---|---|
| `description.xml` `<title>/<author>/<description>` (CDATA) | `label-*`, `author`, `intro-*` |
| `wallpaper/default_wallpaper.jpg` | `wallpaper/wallpaper1.jpg` |
| `wallpaper/default_lock_wallpaper.jpg` | `lockscreen/wallpaper1.jpg` (MIUI `lockscreen` is a MAML engine and does not port) |
| Other raster images under `wallpaper/` | selectable source wallpapers; a selected alternative is used for both home and lock screens |
| `icons` (nested ZIP) → `res/drawable-xxhdpi/<package>.png`, 168–224 px | app icons, named through launcher activities, `ZteSystemApps` aliases and stock stems |
| MIUI aliases such as `com.android.contacts.activities.TwelveKeyDialer.png` | the ZTE app stem (`ZteSystemApps`) |
| `icon_mask.png` / `icon_pattern.png` / `icon_border.png` / `icon_folder.png` | `theme_mask_icon` / `theme_bg_icon-*` / `theme_icon_effect_top` / `theme_folder_icon` |
| `fancy_icons/com.android.calendar/calendar_1..31.png` (full icons) or `bg.png` + `manifest.xml` `<Text>` | calendar strip (full-icon days go on frames 0–30 with a transparent background) |
| `fancy_icons/com.android.deskclock/{bg,hour,minute}.png`, same canvas, hands at 12 o'clock | clock strip, hands rotated +90° |
| `transform_config.xml` PointsMapping (90-unit grid) | icon scale |
| `preview/preview_icons_*.jpg` | extra previews after the generated ones |

### 6.1 MIUI .mtz → MIUI .mtz (recolor)

A recolor is not a port: the theme stays a `.mtz`, and only the pixels inside the `icons` component change.
`MtzRecolor` (pure) decides what each entry of the nested archive is; `MtzRecolorBuilder` acts on it.

| Entry of the `icons` archive | Treatment |
|---|---|
| `res/drawable*/<package or activity>.<png\|webp\|jpg>` | Tinted, and reshaped when a fixed shape is chosen. Left out entirely when "only installed apps" is on and the package is absent |
| `res/drawable*/icon_pattern.png`, `icon_border.png`, `icon_folder*.png` | Tinted, never reshaped: their own pixel size and outline are what the launcher composites against |
| `fancy_icons/**/*.png` (calendar, clock, weather frames) | Tinted only, for the same reason |
| `res/drawable*/icon_mask.png` | Copied. Only its alpha is ever read, so recoloring it would produce a file the launcher cannot tell apart |
| `res/drawable*/status_bar_*` | Copied. Quick-settings toggles tell their on/off states apart by color |
| `*.xml`, directory entries, everything else | Copied byte for byte |

Observed in the two real themes used as fixtures: every entry (outer and nested) is DEFLATED, both archives
carry explicit directory entries, and icons are 224 px `PNG` colour type 6. The rewrite reproduces each entry's
own method and timestamp, and recomputes size and CRC only for entries whose bytes changed, so a theme the
Themes app already accepts still installs.

**Filling gaps.** An app with no drawable in the theme is one MIUI improvises for at runtime — it scales the
app's own icon by `transform_config.xml`, cuts it with `icon_mask.png`, drops it on `icon_pattern.png` and lays
`icon_border.png` over it. That improvisation reads the app's live icon, so it is the one tile a recolor cannot
reach: it stays in the app's own colors while everything around it turns one hue. `MiuiIconArt` assembles the
same composition from already-tinted artwork and writes it in as a real `res/drawable*/<package>.png`.

## 7. Icon pack APK → Nebula

| appfilter.xml | Nebula |
|---|---|
| `<item component="ComponentInfo{pkg/cls}" drawable="…"/>` | `pkg-cls` stem (plus the installed launcher activity) |
| `<iconback img1=…>` | `theme_bg_icon-*` and the plate behind transparent glyphs |
| `<iconmask>` / `<iconupon>` | fixed style: `theme_mask_icon` / `theme_icon_effect_top` |
| `<calendar component=… prefix="calendar_"/>` | calendar strip from `prefix1` … `prefix31` |

Full-size raster artwork in `wallpaper/`, `wallpapers/`, or clearly named `wallpaper*` resources is offered as a vertical selectable-wallpaper list with lazy thumbnails. Discovery excludes previews/thumbnails and images smaller than 480×480.

Drawables are resolved through `resources.arsc`, so resource-obfuscated packs work. Vector-only packs are not supported.

## 8. Build pipeline (app)

1. **Parse** the source (`IconPackSource` / `MtzSource`).
2. **Plan** file names (`IconPlanner`). Earlier rules win:
   1. ZTE system-app assignment;
   2. the exact appfilter component;
   3. the launcher activity installed on the phone;
   4. an activity known from stock themes;
   5. a package-only fallback, always added regardless of whether a component-level name was already found for that package.
3. **Render**:
    - Fixed icons: `None` keeps the imported PNG. A selected fixed outline uses the two sizes and one of the three composition modes above (Overlay, Cover, Crop to background), then writes that flattened PNG locally.
   - The mask, folder, calendar and clock assets for the one selected outline.
   - The selected embedded/custom wallpaper and previews. If Generated is selected, `GeneratedWallpaper` creates the exact exported fallback from three user-editable colors, a Linear/Radial/Soft blobs mix mode and blur. Linear and Radial expose a 0–360° angle; its color-combination action changes the colors, while random placement changes only their locations. The configuration UI previews that exact wallpaper and extracts its plate-color palette locally.
4. **Write** `icons_cur.zip` (`IconsZipWriter`), then the `.zmtp` in stock entry order (`ThemeArchiveWriter`).

### 8.1 Crash diagnostics

Uncaught exceptions are saved under the app's external `files/crash/` directory because NebulaAIOS can mute logcat. The home-screen diagnostic card truncates its display but copies the complete saved report when tapped; its dismiss action clears the retained report. This is app diagnostics only and is not part of the `.zmtp` format.

### 8.2 App visual tokens

The porter UI uses a static, restrained Material Tonal Spot palette rather than Android's wallpaper-driven dynamic scheme: neutral light/dark surfaces, blue-grey emphasis, and 6–24 dp rounded corners. This visual choice is independent of the selected wallpaper; the wallpaper-derived palette remains available solely for icon-plate rendering.

### Confirmed on the device

- **Clock hands:** the +90° rotation is correct.
- **Crop:** layered icons render through `AdaptiveIconDrawable` at a 1.5× zoom, measured from a home-screen screenshot at 1.48–1.52. The app's former variable-shape generator placed cropped artwork in the middle 144 px of `_back` and extended its edges outward, with plate glyphs at 48% of the layer; that generator is now archived (see the note in the intro), but the 1.5×-zoom system fact still holds for any variable-shape theme.
- **Plate alpha:** the porter preserves partial alpha correctly, but the Nubia launcher/theme engine does not composite it as transparent wallpaper color for variable-shape `_back` layers. In `default_theme_Exported_Icon_Pack.zmtp`, 172 ordinary `_back` layers decoded as uniform RGBA `(100, 107, 192, 105)`; the user observed those layers blending with black after the theme was applied. This rules out a PNG/archive premultiplication error in the porter and makes arbitrary translucent variable-shape plates an unsupported engine case. The stock comparison supports that boundary: all `_back` center pixels were opaque in `default_theme_04` (378 files), `13` (325 files) and `49` (52 files); lower edge alpha in some stock files is only shape anti-aliasing. `default_theme_60` has no layered `_back` files. Fixed PNG transparency is not covered by that device result.
- **Shape switching:** a root broadcast of `com.zte.theme.ICON_SHAPE_CHANGE` changed `persist.sys.icon_config_mask` and the launcher's `MiFavor_current_icon` from `config_2` to `config_1` and back, tested on stock `13`, and (when the app still built variable-shape themes and sent this broadcast itself) on an imported `default_theme_ntp_ethereal` once the app was allowed root in KernelSU. That generator and its root broadcast are no longer part of the app; this remains a system fact about the framework mask still needing the theme name to start with `default_theme_`.

- **An assigned system app must ship exactly one file (NX733J, Android 16, 2026-09-21).** Gallery was the only
  system app whose hand-picked icon was ignored: the assignment wrote the stock stem
  `com_android_gallery3d-com_android_newgallery_NewGallery.png`, while the pack's own `com.android.gallery3d`
  artwork separately filled `com_android_gallery3d-com_zte_gallery3d_activity_launcher_MainGallery.png` (the
  activity this device actually exposes, per `cmd package query-activities`) and `com_android_gallery3d.png`. The
  three held different pictures and the pack's default was the one shown. It is the only ZTE system app this can
  reach, because `com.android.gallery3d` is the only one of these packages the packs also ship artwork for -
  every other system app took its manual icon correctly throughout.

  Writing the device's real activity as well did **not** help; only reducing the output to the single stock-named
  file did, confirmed by the user on the device. All four stock themes ship gallery that way. Adding names is
  therefore the wrong instinct here: the stock naming is what the launcher honours, and extra names for the same
  package are what break it.

  Six other system apps name an activity this device does not expose - Messages, Weather, AI Assistant, Compass,
  Wallet and Community - and all of them theme correctly anyway, so a stem naming an absent activity is not by
  itself a problem.

- **A repacked `.mtz` is refused by Xiaomi's Themes app (reported by the user on a Xiaomi device, 2026-09-21).**
  Applying a recolored theme fails with `downloadright|402`. A `.mtz` carries rights metadata the Themes app
  verifies on apply, and 402 is *Payment Required*, so a repacked archive is rejected as unlicensed rather than as
  malformed - a rights check, not a format check. The rewrite preserves entry order, per-entry compression and
  every non-icon component, so this is a licensing boundary rather than an archive defect, and it is not something
  this side can work around. Porting an `.mtz` to a `.zmtp` is unaffected. The recolor feature on
  `mi-theme-tinting` is deprecated for this reason.

### Still to verify

- Whether `theme_info_icon.xml` affects anything; `49` works without it.
