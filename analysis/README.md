# APK static analysis (scoped packages)

Pulled from device `320146880841` (NX733J, nebulaAiOS). Hashes verified against the device after pull.

| Package | APK on device | Version | MD5 | Size | min/target SDK | Decompiled |
|---|---|---|---|---|---|---|
| com.zte.mifavor.launcher | /system_ext/priv-app/Launcher_MFV/Launcher_MFV.apk | 16.0.010.000.2608051737 | 1360fc1f4715c8fe928abff625cc6a84 | 32.8 MB | 31 / 36 | yes (4,556 .java) |
| com.zte.mfvkeyguard | /system/priv-app/MFVKeyguard_MFV/MFVKeyguard_MFV.apk | 16.0.000.000.2607071608 | 1db5f990916357dc630da1deea2417c5 | 76.9 MB | 26 / 36 | yes (5,878 .java) |
| com.zte.mifavor.miboard | /system/priv-app/MiBoard/MiBoard.apk | 16.0.010.000.2607311623 | 4b6997a98f6ed64519a723e67448019c | 26.9 MB | 28 / 36 | yes (6,858 .java) |
| com.android.systemui | /system_ext/priv-app/SystemUI_MFV/SystemUI_MFV.apk | 16.0.000.101.2608051725 | be988183977cc2a36d02ffbdf1a3f847 | 287.2 MB | 31 / 36 | yes (18,462 .java from 13,225 classes; sharedUserId `android.uid.systemui`) |

APKs live in `analysis/apk/` and decompiled output in `analysis/work/` (both gitignored, see `.gitignore`).

## How SystemUI was decompiled
Multi-thread runs (`-j 2`, `-j 6`) and a 2 GB heap failed: the JVM ran out of commit memory (DOS errno 1455), or exited with no log. The run that worked:
`JAVA_OPTS="-Xmx3g" jadx -j 1 -r --no-debug-info -v -d analysis/work/SystemUI_MFV analysis/apk/SystemUI_MFV.apk` (exit code 3, the normal per-method failures).

## Known gaps
- jadx exit code 3 on all four means some methods failed to decompile (e.g. `androidx.exifinterface` and `material.carousel` in SystemUI); the errors are in `analysis/work/*.jadx.log`.
- Resources were skipped (`-r`), so layouts and XML are not in the SystemUI output. Use `aapt2` on the APK if you need them.

## Hook class check
`analysis/work/hook-class-literals.txt` lists the 39 `com.android.*` / `com.zte.*` string literals found in `xposed-android/.../hook/`. Confirmed present in the decompiled APKs:
- MiBoard: `contents.views.g`, `contents.express.ExpressCard`
- Launcher: `Hotseat`, `Workspace`, `folder.Folder`, `folder.FolderIcon`, `popup.PopupContainerWithArrow`, `views.OptionsPopupView`, `views.ScrimView`, `allapps.LauncherAllAppsContainerView`, `widget.WidgetsBottomSheet`, `oneshot.OneShotBlurLayout`, `pageindicators.PageIndicatorDots`, `hotseat.HotseatRecentCellLayout`
- Keyguard: `zte.lockscreen.base.clockView.*` (ClockView, SingleClockView, DoubleClockView, ClockStatusView, single.DateView), `lockView.BaseLockView`, `lockView.ImageLockView`, `lockView.CustomImageView`

SystemUI targets, all present: `com.android.systemui.statusbar.KeyguardAffordanceView`, `com.android.systemui.statusbar.notification.row.NotificationBackgroundView`, `com.zte.controlcenter.view.ControlCenterMediaPlayerView`, `com.zte.feature.dynamicisland.NotificationMediaPanelBackground` / `NotificationMediaPanelNoBlurBg`, `com.zte.feature.keyguardwidget.KeyguardWidgetContainer`, `com.zte.mifavor.qs.QSDetail`, `com.zte.qs.tileimpl.QSTileViewCircle` / `QSTileViewWide`.

Keyguard classes (`com.zte.lockscreen.*`) and `com.zte.mifavor.keyguard.personalclock.*` belong to MFVKeyguard, not SystemUI, so they show as missing when checked against the SystemUI sources.

## SystemUI manifest notes
- `sharedUserId="android.uid.systemui"`, 19 receivers, and a ZTE providers set (`com.android.systemui.zte`, `com.zte.feature.doze`, `com.zte.feature.dynamicisland.settings`, `com.zte.mifavor.keyguard.settings`).
- Permissions in use include `zte.permission.START_GESTURE_GUIDE` and `com.zte.systemui.permission.SHELL_PROXY_SERVICE`.
- Build string: `MiFavor 16.0.010.001 2026-07-27 11:32`.
- Full xmltree: `analysis/work/SystemUI_MFV.manifest.xmltree`.

Ignore `com.android.launcher3.blur.$suffix`: it is a template artifact, not a real class.
