# nebulaColorize, build of 2026-11-23

For the Nubia Z70 Ultra (NX733J) on NebulaAIOS 2, with root and Vector.

| File | What it is | md5 |
| --- | --- | --- |
| `nebulaColorize-2026-11-23.apk` | This build (debug-signed) | `38d996679b5458b9826aa62c0bad47c9` |
| `rollback-before-lock-layout.apk` | The build from just before the lock-screen layout: no resizable clock, widget grid or bottom layout | `e3a7676e55a7359d99d871a69d4b2084` |

## Install or roll back

1. `adb install -r <apk>`. Both files install over each other and keep your settings.
2. In Vector, the module must be enabled for Launcher, System UI, Lock screen (`com.zte.mfvkeyguard`) and Widget board (`com.zte.mifavor.miboard`).
3. In the app: **Apply to → Restart apps**. Don't do this with the lock-screen editor open, or its unapplied edits are lost.

If System UI misbehaves, turn the module off in Vector and restart, or install the rollback.

## Everything you can configure

### App, Glass tab

| Setting | Range | What it does |
| --- | --- | --- |
| Chip beside the title | All surfaces, Notifications, Control centre, Lock screen, Widget board | What you're editing. Editing one gives it its own look. "Match all surfaces again" in the same menu undoes that. |
| Clear / Tinted | | Neutral glass, or coloured glass |
| Colour bar | any hue | The tint. There are also system-colour, wallpaper-colour and eyedropper buttons. |
| Intensity bar | 0–100 % | From Clear to fully tinted |
| Blur | 0–200 % | How frosted what's behind is (not on the widget board) |
| Glow | 0–300 % | The lit edge |
| Dim | 0–150 % | How dark it is behind notifications and the control centre |

Blur, Glow and Dim snap at 100 %, the tuned value.

### App, Apply to tab

| Setting | What it does |
| --- | --- |
| Home screen | Folder tiles, long-press menu, clock widget, app widgets. Tap the row for each one's switch. |
| Dock | Glass dock |
| Widget board | Glass cards |
| Lock screen | Clock and dates, widgets, shortcuts, notifications and player. Tap the row for each one's switch. |
| Notifications | Glass shade |
| Control centre | Glass tiles |
| Widgets and shortcuts at bottom | The iOS positions |
| Notifications at bottom | Cards stack up from just above the widgets or shortcuts |
| Clock size and boldness | Opens the lock-screen list. Tap Edit there. |
| Restart apps | Reloads the module after an install (needs root) |
| Reset look | Clear, with Blur, Glow and Dim at 100 %. Switches and layout stay. |

### Lock-screen editor

| Control | What it does |
| --- | --- |
| Arc at the clock's lower right | Drag to make the clock taller or shorter |
| Wedge above the Wallpaper button | Boldness |
| ↺ beside the wedge | Back to Nubia's own clock and font |
| Nubia's widget picker | Up to five columns: a round widget takes one, a wide one two |
| Cancel / Apply | Discards or keeps the clock change, like Nubia's own edits |

## Limits

- Only the one-line digital clock style can be resized. A resized clock uses RobotoFlex in place of Nubia's font.
- The editor previews solid digits, with widgets under the clock. The glass and the bottom layout show on the real lock screen.
- The clock gets shorter by itself when notifications need the room.
- Widget rows with fewer than five columns filled sit centred, so they don't reach the cards' edges.
- The app is English only.

## Not yet exercised on the phone

- The resize handle after its final move out of the back-gesture strip
- A wide widget under the final two-column rule
- Heads-up and grouped notifications, and a lock screen with none
- Enrolled fingerprints, 12-hour time, landscape, live wallpapers and the depth effect

The full list is in [docs/HANDOFF.md](../../docs/HANDOFF.md#not-verified).
