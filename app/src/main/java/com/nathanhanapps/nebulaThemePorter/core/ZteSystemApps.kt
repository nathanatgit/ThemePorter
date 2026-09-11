package com.nathanhanapps.nebulaThemePorter.core

import java.util.Locale

/**
 * A preinstalled NebulaAIOS app. [stems] are the file names stock themes use for it; [aliases] are the package
 * or drawable names other ROMs and icon packs use for the equivalent app, most specific first.
 */
data class ZteSystemApp(
    val id: String,
    val labelEn: String,
    val labelZh: String,
    val stems: List<String>,
    val aliases: List<String>,
)

object ZteSystemApps {
    val all: List<ZteSystemApp> = listOf(
        app(
            "phone", "Phone", "电话",
            stems = listOf("com_android_contacts-com_android_contacts_activities_DialtactsActivity"),
            aliases = listOf(
                "com.android.contacts.activities.TwelveKeyDialer", "com.google.android.dialer", "com.android.dialer",
                "com.samsung.android.dialer", "com.oneplus.dialer", "com.android.incallui", "com.android.phone",
            ),
        ),
        app(
            "contacts", "Contacts", "联系人",
            stems = listOf("com_android_contacts-com_android_contacts_activities_PeopleActivity"),
            aliases = listOf(
                "com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts",
            ),
        ),
        app(
            "messages", "Messages", "短信",
            stems = listOf(
                "com_android_messaging-ui_conversationlist_ConversationListActivity",
                "com_android_mms-com_android_mms_ui_ConversationList",
            ),
            aliases = listOf(
                "com.android.mms", "com.google.android.apps.messaging", "com.android.messaging",
                "com.samsung.android.messaging",
            ),
        ),
        app(
            "browser", "Browser", "浏览器",
            stems = listOf(
                "com_ume_browser-com_ume_browser_MainActivity",
                "cn_nubia_browser-com_android_browser_BrowserLauncher",
                "com_zte_nubrowser-com_android_browser_BrowserLauncher",
            ),
            aliases = listOf(
                "com.android.browser", "com.miui.browser", "com.miui.hybrid", "com.sec.android.app.sbrowser", "com.heytap.browser",
                "com.huawei.browser", "com.vivo.browser",
            ),
        ),
        app(
            "camera", "Camera", "相机",
            stems = listOf(
                "com_zte_camera-com_zte_camera_CameraActivity",
                "com_android_camera-com_android_camera_CameraLauncher",
                "com_android_camera2-com_android_camera_CameraLauncher",
            ),
            aliases = listOf(
                "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera",
                "com.sec.android.app.camera", "com.oplus.camera", "com.huawei.camera", "com.mlab.cam",
            ),
        ),
        app(
            "gallery", "Gallery", "图库",
            stems = listOf("com_android_gallery3d-com_android_newgallery_NewGallery"),
            aliases = listOf(
                "com.miui.gallery", "com.android.gallery3d", "com.sec.android.gallery3d", "com.coloros.gallery3d",
                "com.google.android.apps.photos",
            ),
        ),
        app(
            "settings", "Settings", "设置",
            stems = listOf("com_android_settings-com_android_settings_Settings"),
            aliases = listOf("com.android.settings"),
        ),
        app(
            "calendar", "Calendar", "日历",
            stems = listOf("com_android_calendar-com_android_calendar_AllInOneActivity"),
            aliases = listOf(
                "com.android.calendar", "com.android.calendary", "com.xiaomi.calendar", "com.google.android.calendar",
                "com.samsung.android.calendar",
            ),
        ),
        app(
            "clock", "Clock", "时钟",
            stems = listOf("zte_com_cn_alarmclock-zte_com_cn_alarmclock_activity_AlarmClockActivity"),
            aliases = listOf(
                "com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage",
            ),
        ),
        app(
            "calculator", "Calculator", "计算器",
            stems = listOf("com_android_calculator2-com_android_calculator2_Calculator"),
            aliases = listOf(
                "com.miui.calculator", "com.android.calculator2", "com.google.android.calculator",
                "com.sec.android.app.popupcalculator",
            ),
        ),
        app(
            "files", "Files", "文件管理",
            stems = listOf("zte_com_cn_filer-zte_com_cn_filer_FileMgrActivity"),
            aliases = listOf(
                "com.android.fileexplorer", "com.miui.filer", "com.google.android.apps.nbu.files",
                "com.google.android.documentsui", "com.android.documentsui", "com.sec.android.app.myfiles",
            ),
        ),
        app(
            "recorder", "Recorder", "录音机",
            stems = listOf("cn_zte_recorder-cn_zte_recorder_RecordHomeActivity"),
            aliases = listOf(
                "com.android.soundrecorder", "com.google.android.apps.recorder", "com.sec.android.app.voicenote",
            ),
        ),
        app(
            "notes", "Notes", "笔记",
            stems = listOf("cn_nubia_notepad_preset-cn_nubia_notepad_NoteListActivity"),
            aliases = listOf("com.miui.notes", "com.samsung.android.app.notes", "com.google.android.keep"),
        ),
        app(
            "music", "Music", "音乐",
            stems = listOf("cn_zte_music-cn_zte_music_activity_MusicBrowserActivity"),
            aliases = listOf("com.miui.player", "com.android.music", "com.heytap.music", "com.oppo.music"),
        ),
        app(
            "video", "Video", "视频",
            stems = listOf("com_zte_videoplayer-com_zte_videoplayer_videobrowseractivity"),
            aliases = listOf("com.miui.video", "com.miui.mediaviewer", "com.android.videoplayer"),
        ),
        app(
            "weather", "Weather", "天气",
            stems = listOf(
                "com_zte_mifavor_weather-com_zte_mifavor_weather_ui_HomeActivity",
                "com_zte_weather-ui_HomeActivity",
                "com_icoolme_android_zteweather-com_icoolme_android_weather_activity_SmartWeatherActivity",
            ),
            aliases = listOf("com.miui.weather2", "com.coloros.weather2", "com.sec.android.daemonapp"),
        ),
        app(
            "app_store", "App Store", "应用商店",
            stems = listOf(
                "zte_com_market-zte_com_market_LoginActivity",
                "cn_nubia_neostore-cn_nubia_neostore_ui_start_AppStartActivity",
            ),
            aliases = listOf(
                "com.xiaomi.market", "com.miui.supermarket", "com.heytap.market", "com.huawei.appmarket", "com.bbk.appstore",
                "com.oppo.market",
            ),
        ),
        app(
            "game_center", "Game Center", "游戏中心",
            stems = listOf(
                "com_zte_quickgame-com_zte_quickgame_ZteMainActivity",
                "cn_nubia_neogamecenter-cn_nubia_neostore_ui_start_AppStartActivity",
            ),
            aliases = listOf("com.xiaomi.gamecenter", "com.miui.gamecenter"),
        ),
        app(
            "game_space", "Game Space", "游戏空间",
            stems = listOf("cn_nubia_gamelauncher-cn_nubia_gamelauncher_GameSpaceActivity"),
            aliases = listOf("com.xiaomi.migameservice", "com.miui.securityadd", "com.google.android.play.games"),
        ),
        app(
            "themes", "Themes", "主题",
            stems = listOf("com_zte_beautify-com_zte_beautify_view_beauty_splashscreen_LogoActivity"),
            aliases = listOf(
                "com.android.thememanager", "com.samsung.android.themestore", "com.heytap.themestore",
            ),
        ),
        app(
            "phone_manager", "Phone Manager", "手机管家",
            stems = listOf("com_zte_heartyservice-com_zte_heartyservice_main_HeartServiceActivityLauncher"),
            aliases = listOf(
                "com.miui.securitycenter", "com.miui.securitymain.SCMainEntryActivity", "com.coloros.phonemanager",
                "com.samsung.android.lool",
            ),
        ),
        app(
            "voice_assistant", "Voice Assistant", "智慧语音",
            stems = listOf("com_zte_halo_app-com_zte_halo_app_help_VoiceSettings"),
            aliases = listOf("com.miui.voiceassist", "com.xiaomi.mico", "com.android.voicedialer"),
        ),
        app(
            "translate", "Translate", "同声传译",
            stems = listOf("com_zte_halo_app-com_zte_halo_app_translate_ui_FaceToFaceTranslate"),
            aliases = listOf("com.google.android.apps.translate"),
        ),
        app(
            "ai_assistant", "AI Assistant", "AI 助手",
            stems = listOf("com_zte_aiassistant-com_zte_aiassistant_ui_activity_MainActivity"),
            aliases = listOf("com.miui.personalassistant"),
        ),
        app(
            "service", "Service", "服务",
            stems = listOf("com_zte_handservice-com_zte_handservice_MainActivity"),
            aliases = listOf("com.miui.miservice"),
        ),
        app(
            "user_guide", "User Guide", "用户指南",
            stems = listOf("com_zte_userguide-com_zte_userguide_MainActivity"),
            aliases = listOf("com.miui.bugreport"),
        ),
        app(
            "downloads", "Downloads", "下载",
            stems = listOf(
                "com_android_providers_downloads_ui-com_android_providers_downloads_ui_DownloadList",
                "com_android_providers_downloads-com_android_providers_downloads_ui_DownloadList",
            ),
            aliases = listOf("com.android.providers.downloads.ui", "com.android.providers.downloads"),
        ),
        app(
            "search", "Search", "搜索",
            stems = listOf("com_zte_mifavor_zsearch-com_zte_mifavor_zsearch_SearchMainAty"),
            aliases = listOf("com.android.quicksearchbox", "com.google.android.googlequicksearchbox"),
        ),
        app(
            "email", "Email", "电子邮件",
            stems = listOf("com_mobimail_zte-com_netease_mobimail_activity_LaunchActivity"),
            aliases = listOf("com.android.email", "com.google.android.gm", "com.samsung.android.email.provider"),
        ),
        app(
            "fm_radio", "FM Radio", "收音机",
            stems = listOf("com_quicinc_fmradio-com_quicinc_fmradio_FMRadio"),
            aliases = listOf("com.miui.fm", "com.android.fmradio", "com.caf.fmradio"),
        ),
        app(
            "compass", "Compass", "指南针",
            stems = listOf("com_zte_cn_compass-com_zte_cn_compass_CompassActivity"),
            aliases = listOf("com.miui.compass"),
        ),
        app(
            "wallet", "Wallet", "卡包",
            stems = listOf("com_zte_wallet-com_zte_wallet_bus_BusActivityCardEntrance"),
            aliases = listOf("com.mipay.wallet", "com.google.android.apps.walletnfcrel", "com.samsung.android.spay"),
        ),
        app(
            "health", "Health", "运动健康",
            stems = listOf("com_zte_sports-com_zte_sports_home_MainActivity"),
            aliases = listOf(
                "com.mi.health", "com.miui.misupport", "com.xiaomi.hm.health", "com.google.android.apps.fitness",
                "com.samsung.android.app.shealth",
            ),
        ),
        app(
            "community", "Community", "社区",
            stems = listOf(
                "cn_zte_bbs-cn_zte_bbs_ui_activity_WelcomeActivity",
                "cn_nubia_bbs-cn_nubia_bbs_activity_SplashActivity",
            ),
            aliases = listOf("com.xiaomi.vipaccount", "com.xiaomi.bbs"),
        ),
        app(
            "mall", "Mall", "商城",
            stems = listOf("com_zte_zmall-com_zte_zmall_MainActivity"),
            aliases = listOf("com.xiaomi.shop"),
        ),
        app(
            "kids", "Kids Space", "儿童空间",
            stems = listOf("com_zte_kidszone-com_android_launcher3_Launcher"),
            aliases = listOf("com.miui.kidspace"),
        ),
        app(
            "remote", "Remote Control", "遥控器",
            stems = listOf("com_zte_remotecontroller-com_kookong_app_MainActivity"),
            aliases = listOf("com.duokan.phone.remotecontroller"),
        ),
        app(
            "smart_home", "Smart Home", "智慧生活",
            stems = listOf("com_zte_smarthome-com_zte_smarthome_ui_SplashActivity"),
            aliases = listOf("com.xiaomi.smarthome", "com.google.android.apps.chromecast.app"),
        ),
        app(
            "phone_clone", "Phone Clone", "互传",
            stems = listOf("cuuca_sendfiles_Activity-com_ume_weshare_activity_NewMainActivity"),
            aliases = listOf("com.miui.huanji"),
        ),
        app(
            "share", "Share", "分享",
            stems = listOf("com_zte_km_share-cn_com_zte_share_ui_activity_SplashActivity"),
            aliases = listOf("com.miui.mishare.connectivity"),
        ),
        app(
            "cast", "Screen Cast", "投屏",
            stems = listOf("com_zte_smartcast-com_zte_smartcast_activity_CastMainActivity"),
            aliases = listOf("com.milink.service", "com.xiaomi.mirror"),
        ),
        app(
            "earbuds", "Earbuds", "蓝牙耳机",
            stems = listOf("com_zte_livebudsapp-com_zte_livebudsapp_home_MainActivity"),
            aliases = listOf("com.xiaomi.bluetooth", "com.android.bluetooth"),
        ),
        app(
            "roaming", "Roaming", "漫游",
            stems = listOf("com_redteamobile_roaming-com_redteamobile_roaming_activity_MainActivity"),
            aliases = listOf("com.miui.virtualsim"),
        ),
        app(
            "reader", "Reader", "阅读",
            stems = listOf(
                "com_chaozh_iReaderNubia-com_chaozh_iReaderNubia_ui_activity_WelcomeActivity",
                "com_shuqi_controller-com_shuqi_controller_Loading",
            ),
            aliases = listOf("com.duokan.reader"),
        ),
        app(
            "face_unlock", "Face Unlock", "人脸识别",
            stems = listOf("com_zte_faceverify-com_zte_faceverify_FaceVerifyDispatchActivity"),
            aliases = emptyList(),
        ),
        app(
            "home_settings", "Home Screen", "桌面设置",
            stems = listOf("com_zte_mifavor_launcher-com_android_launcher3_settings_HomeScreenLayoutActivity"),
            aliases = listOf("com.miui.home"),
        ),
        app(
            "chrome", "Chrome", "Chrome",
            stems = listOf("com_android_chrome-com_google_android_apps_chrome_Main"),
            aliases = listOf("com.android.chrome"),
        ),
    )

    private val byAlias: Map<String, ZteSystemApp> = buildMap {
        all.forEach { app -> app.aliases.forEach { putIfAbsent(it.lowercase(Locale.ROOT), app) } }
    }

    fun byId(id: String): ZteSystemApp? = all.firstOrNull { it.id == id }

    fun matchAlias(key: String): ZteSystemApp? = byAlias[key.lowercase(Locale.ROOT)]

    private fun app(
        id: String,
        labelEn: String,
        labelZh: String,
        stems: List<String>,
        aliases: List<String>,
    ) = ZteSystemApp(id, labelEn, labelZh, stems, aliases)
}

/** Package-to-activity stems from stock themes, used when a source names a package but not its activity. */
class StockComponentIndex(stems: Collection<String>) {
    private val byPackage: Map<String, List<String>> = stems
        .filter { '-' in it && AppComponent.isSafeStem(it) }
        .distinct()
        .groupBy { it.substringBefore('-') }

    fun stemsFor(packageStem: String): List<String> = byPackage[packageStem].orEmpty()

    val size: Int get() = byPackage.values.sumOf { it.size }

    companion object {
        fun parse(text: String): StockComponentIndex = StockComponentIndex(
            text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList(),
        )
    }
}
