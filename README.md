# Nebula Theme Porter

`com.nathanhanapps.nebulaThemePorter` is an Android app written in Kotlin with Jetpack Compose. On the phone, it converts either of these into a NebulaAIOS theme (`.zmtp`):

- an **icon pack APK** in ADW/Nova `appfilter.xml` format, or
- a **MIUI/HyperOS theme** (`.mtz`).

The interface uses a restrained Material Tonal Spot palette in both light and dark mode: neutral surfaces with one blue-grey emphasis color, independent of the phone wallpaper. Wallpaper colors are still available only where they are useful—when choosing an icon plate.

It can produce two kinds of theme:

- **Variable shape:** layered `_back`/`_front` icons, five shape configs, and per-shape masks, folders, calendar and clock. After applying, switch the icon shape from Nebula Theme Porter's home screen. This needs root, because the Themes app only shows its shape picker for the built-in `default_theme_04`, `13` and `49`.
- **Fixed shape:** one flat PNG per app, as in `default_theme_60` and most community ports. `None` preserves the imported PNG; otherwise the app bakes a chosen outline and optional color plate into every final PNG. That same selected outline is used for unthemed-app plates, folders, dynamic calendar/clock artwork and the shortcut plate. The baked choices include the five Nebula shapes plus hexagon, octagon, diamond, shield, arch, petal, clover and cookie, without depending on the system’s shape picker.

The theme structure the app builds is mapped in [docs/NEBULA_THEME_FORMAT.md](docs/NEBULA_THEME_FORMAT.md).

## Using it

1. Grant **All files access** when the home screen asks. Files are chosen with the system picker. With the permission, a file picked from the phone's storage is read in place, and themes are saved straight into `Internal storage/Theme`. The output folder can be changed in the settings screen with the system folder picker, which cannot select the storage root or Download. Without the permission, picked files are copied first and you choose where to save each theme.
2. Choose an installed icon pack, an icon-pack APK file, or an `.mtz`. Installed-pack detection runs only when opened and lists launcher-visible apps that actually contain `appfilter.xml`; selecting one reads its installed `base.apk` directly.
3. Adjust the name and style. Variable-shape themes use the five system masks. Fixed themes hide the unrelated unthemed-app shape control: their selected fixed outline applies universally, including the launcher plate behind apps not present in the icon pack. The fixed-shape grid ends in a **Custom PNG** tile that selects the optional shape background. For a pre-rendered shape, the live preview has an independent **background size** slider; **icon size** is shown only where it changes the result (not for **Cover**, which always fills the selected shape). The fixed modes are **Overlay** (tinted plate behind the icon), **Cover** and **Crop to background**. Overlay has an icon-transparency-strength slider for foreground/plate blending. The **Material You palette** groups collapsible Background tint and Icon tint panels; both use the same compact wallpaper-color swatches. Any fixed mode can use grayscale-aware icon colorization from the selected palette color, with direct Hue/Saturation/Brightness and strength sliders while retaining the original alpha outline. The recolor target is calculated by multiplying the icon’s grayscale values with the selected tint. A custom background PNG should be square and at least 512×512 px, with centred artwork and a little edge bleed because it will be cropped to the selected shape. The preview selector shows five common imported icons plus a manual picker; it affects the preview only, never icon assignment or export, and slider changes are coalesced before its background render. The wallpaper section follows it and presents Generated plus source wallpapers as a vertical thumbnail list. Generated has one gradient studio: three editable colors, Linear/Radial/Soft blobs mixing, blur, **New color combination**, and **Randomize placement**. Linear and Radial also expose a 0–360° angle slider; random placement changes only the colors’ positions. Embedded and external wallpaper choices remain available. The partial-alpha caution applies only to variable-shape layers; fixed PNG transparency is not constrained by that warning.
4. Override icons for supported system apps that are currently installed and launcher-visible. The folded User apps section loads launcher-visible user-installed apps only when opened and lets any of them use an icon from the source.
5. Build, then apply the theme from the Themes (主题) app.

Everything runs offline, and the app has no Internet permission.

## Project layout

| Path | Contents |
|---|---|
| `core/` | Plain Kotlin with no Android imports. Holds the theme spec (`NebulaSpec`, `IconShape`, `ShapeAsset`, `ThemeLayout`), XML templates, the ZTE system-app table, the appfilter and MTZ parsers, the icon naming planner and the ZIP writers. All of it is unit tested on the JVM. |
| `render/` | Bitmap work: layer splitting, shape masks, folder plates, calendar and clock strips, previews. |
| `source/` | `IconPackSource` and `MtzSource`, which read images lazily from the archives, plus installed launcher activities. |
| `build/ThemeBuilder.kt` | Renders every asset and writes `icons_cur.zip` and the `.zmtp`. |
| `storage/` | All-files access, default folders, preferences. |
| `ui/` | Compose screens and the ViewModel. |
| `assets/overlays/` | The three stock overlay APKs from `default_theme_13`, copied into every theme. |
| `assets/stock_components.txt` | The 385 package-activity stems found in the stock themes. |

## Build and test

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
# Optional: also compare the mapped layout with the real stock themes
$env:NEBULA_STOCK_THEMES='C:\Users\Han\Project\nebulaThemePorter\stockThemes'
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

The wrapper uses Gradle 9.5.0 with AGP 9.3.2 and runs successfully on Android Studio's Java 25 runtime. App bytecode still targets Java 17. Without `NEBULA_STOCK_THEMES`, the stock regression tests are skipped.

## Debugging on a device

Debug builds include `DebugBuildActivity`, which runs the whole pipeline without the UI. Push an input into the app's external files directory, then start the activity:

```powershell
adb push theme.mtz /sdcard/Android/data/com.nathanhanapps.nebulaThemePorter/files/
adb shell am start -n com.nathanhanapps.nebulaThemePorter/.debug.DebugBuildActivity --es source theme.mtz --es output out.zmtp --es style ADAPTIVE --ez onlyInstalled true
adb shell cat /sdcard/Android/data/com.nathanhanapps.nebulaThemePorter/files/out.zmtp.status.txt
```

The status file starts with `RUNNING`, `DONE` or `FAILED`; a failure includes the stack trace.

NebulaAIOS mutes logcat by default. Use `adb shell setprop log.tag I` to enable it, and `adb shell setprop log.tag S` to mute it again. The app also keeps uncaught exceptions in `files/crash/` and shows the latest one on the home screen.

The home-screen crash card keeps the full saved stack trace: tap the card or **Copy report** to copy it, long-press the visible text to select it, and use **Dismiss** only after saving any report you need.
