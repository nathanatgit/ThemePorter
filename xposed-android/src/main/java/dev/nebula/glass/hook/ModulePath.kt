package dev.nebula.glass.hook

import de.robv.android.xposed.IXposedHookZygoteInit
import dev.nebula.glass.ClockFonts

/** Tells [ClockFonts] where the module's APK is, so the hooks can read the clock fonts from it. */
class ModulePath : IXposedHookZygoteInit {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ClockFonts.apkPath = startupParam.modulePath
    }
}
