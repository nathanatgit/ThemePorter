# Nebula Theme Porter

Nebula Theme Porter converts icon packs and MIUI/HyperOS themes into NebulaAIOS themes (`.zmtp`), entirely on your phone. It's built for ZTE and nubia phones running Mifavor OS / NebulaAIOS.

Everything runs offline. The app has no Internet permission, reads only the source file you pick, and writes only the theme it builds.

## What it can convert

- An **icon pack APK** (ADW/Nova-style, with an `appfilter.xml`) — either one already installed on your phone or an APK file.
- A **MIUI/HyperOS theme** (`.mtz`) — icons, wallpaper, folders, calendar and clock.

The result is a `.zmtp` file you apply from the phone's built-in **Themes** app, just like any other theme.

## Getting started

<img src="docs/screenshots/home.jpg" alt="Home screen: choose an installed icon pack, an icon-pack APK, or a MIUI theme" width="320">

1. **Grant "All files access"** when the home screen asks. With it, a file you pick is read in place and the finished theme is saved straight into `Internal storage/Theme`. You can change the output folder from the system folder picker (it can't select the storage root or Download). Without the permission, the app copies the source first and asks where to save each result.
2. **Choose a source** on the home screen:
   - **Installed icon pack** — pick from apps already on your phone that contain an `appfilter.xml`.
   - **Icon pack APK file** — pick any icon-pack APK from storage.
   - **MIUI theme** — pick a `.mtz` file.

## Customizing the theme

<img src="docs/screenshots/fixed-shape-and-preview.jpg" alt="Configure screen: fixed app-icon shape gallery, composition mode and a live icon preview" width="320">

- **Theme info** — set the Chinese/English names, author and description shown in the Themes app, and the output file name.
- **Fixed app-icon shape** — pick one shape to bake into every app icon, or **None** to keep each imported icon's original artwork untouched. The gallery includes Circle, Square, Samsung Squircle, iOS Squircle, Slanted, Fan, Pentagon, Gem, Sunny, 6/9/12-sided Cookie, 4/8-leaf Clover, Soft Burst, Flower, Ghostish, Pixel Circle and Heart.
- **Composition**, once a shape is chosen:
  - **Overlay** — a tinted plate sits behind the icon, so the imported artwork stays fully visible on top.
  - **Cover** — the icon is cropped to completely fill the shape.
  - **Crop to background** — the icon keeps its imported size but is clipped to the shape's outline.
- **Icon size, transparency and background size** sliders fine-tune how the icon sits on its plate; a live preview (five common apps, or pick any imported icon) updates as you adjust them.
- **Background tint and icon tint** — pick a color from the current wallpaper's palette, a neutral, or a custom color. Icon tint recolors the imported glyph from its own grayscale shading while keeping its transparent outline, with independent hue/saturation/brightness and strength controls.
- **Custom background image** — instead of a flat color, use your own square PNG (512×512 px or larger, subject centered with a little edge bleed) as the shape's plate.
- **Wallpaper** — use the source's own wallpaper if it has one, or the built-in gradient studio (three colors, Linear/Radial/Soft-blob mixing, blur, and one-tap recoloring/reshuffling).
- **Options** — limit the theme to apps actually installed on your phone, generate a plain calendar/clock when the source has none, and generate a temporary icon (from the app's own launcher icon) for installed apps missing from the source, optionally keeping that app's own icon background instead of the theme's unified color.
- **Manual replacement** — override the suggested icon for any supported system app, or any other installed, launcher-visible app, from the source's own icon list.

## Building and applying

<img src="docs/screenshots/saved.jpg" alt="Done screen: theme saved, with a button to open the Themes app" width="320">

Tap **Build into…**, then open the **Themes** app and apply it from there like any other theme.

## Good to know

- **A rebuilt theme with the same file name may still show its old preview picture in the Themes app.** The Themes app appears to cache preview thumbnails by theme name rather than by content, so overwriting a `.zmtp` under the same name doesn't always refresh what it displays. If a freshly rebuilt theme looks unchanged in the Themes app's preview, give the output a new file name, or clear the Themes app's storage/cache from Android's app settings.
- The app never asks the system to switch icon shape live — every fixed-shape icon is pre-rendered into the theme itself, so there's no need for root access.

## Docs for developers

The theme file format this app targets is documented in [docs/NEBULA_THEME_FORMAT.md](docs/NEBULA_THEME_FORMAT.md), reverse-engineered from ZTE/nubia's stock themes.
