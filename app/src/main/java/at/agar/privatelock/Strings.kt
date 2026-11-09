package at.agar.privatelock

import android.content.Context
import java.util.Locale

/** Alle Texte der App – eine Instanz pro Sprache (Dateien im Ordner lang/). Fehlt ein Text, baut die App nicht (gewollt). */
class Strings(
    val back: String,
    val settings: String,
    val statusAdminMissing: String,
    val statusDriveCar: String,
    val statusActive: String,
    val statusOff: String,
    val adminMissingSub: String,
    val allow: String,
    val allowed: String,
    val granted: String,
    val sensitivity: String,
    val low: String,
    val medium: String,
    val high: String,
    val graphCaption: String,
    val modeNormal: String,
    val modeDrive: String,
    val modeBag: String,
    val modeNormalText: String,
    val modeDriveText: String,
    val modeBagText: String,
    val testButton: String,
    val testCaption: String,
    val adminOpenFail: String,
    val adminGranted: String,
    val adminDenied: String,
    val testRunning: String,
    val testEnded: String,
    val detectedGrab: String,
    val detectedFall: String,
    val testWouldLock: String,
    val orbOn: String,
    val orbOff: String,
    val orbHintOn: String,
    val orbHintOff: String,
    val orbDesc: String,
    val secDetection: String,
    val secOnLock: String,
    val secAuto: String,
    val secPermissions: String,
    val secLanguage: String,
    val smartTitle: String,
    val smartSub: String,
    val fallTitle: String,
    val fallSub: String,
    val delayTitle: String,
    val delaySub: String,
    val delayNow: String,
    val delayShort: String,
    val delayLong: String,
    val vibrateTitle: String,
    val vibrateSub: String,
    val alarmTitle: String,
    val alarmSub: String,
    val notifyTitle: String,
    val notifySub: String,
    val bootTitle: String,
    val bootSub: String,
    val driveTitle: String,
    val driveSub: String,
    val screenOffTitle: String,
    val screenOffSub: String,
    val adminTitle: String,
    val adminSub: String,
    val batteryTitle: String,
    val batterySub: String,
    val batteryDone: String,
    val change: String,
    val notificationsTitle: String,
    val notificationsSub: String,
    val uninstallTitle: String,
    val uninstallSub: String,
    val adminRemoved: String,
    val btDenied: String,
    val btOk: String,
    val version: String,
    val languageTitle: String,
    val languageAuto: String,
    val notifActiveTitle: String,
    val notifActiveText: String,
    val modeDriveCar: String,
    val turnOff: String,
    val lockFailTitle: String,
    val lockFailText: String,
    val lockedTitle: String,
    val lockedGrab: String,
    val lockedFall: String,
    val chStatus: String,
    val chStatusDesc: String,
    val chEvents: String,
    val chEventsDesc: String,
    val tileOn: String,
    val tileOff: String,
    val adminExplain: String
)

/** Sprache wählen: automatisch (Handy-Sprache) oder fest eingestellt */
object L {
    /** Code + Name in der jeweiligen Sprache */
    val LANGS: List<Pair<String, String>> = listOf(
        "de" to "Deutsch",
        "en" to "English",
        "id" to "Bahasa Indonesia",
        "bs" to "Bosanski",
        "es" to "Español",
        "fr" to "Français",
        "hr" to "Hrvatski",
        "it" to "Italiano",
        "sw" to "Kiswahili",
        "ku" to "Kurdî",
        "hu" to "Magyar",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "pt" to "Português",
        "ro" to "Română",
        "sq" to "Shqip",
        "sk" to "Slovenčina",
        "sr" to "Srpski",
        "sv" to "Svenska",
        "vi" to "Tiếng Việt",
        "tr" to "Türkçe",
        "cs" to "Čeština",
        "el" to "Ελληνικά",
        "bg" to "Български",
        "ce" to "Нохчийн",
        "ru" to "Русский",
        "uk" to "Українська",
        "he" to "עברית",
        "ur" to "اردو",
        "ar" to "العربية",
        "fa" to "فارسی",
        "hi" to "हिन्दी",
        "bn" to "বাংলা",
        "th" to "ไทย",
        "zh" to "中文",
        "ja" to "日本語",
        "ko" to "한국어"
    )

    /** Sprachen, die von rechts nach links geschrieben werden */
    private val RTL = setOf("ar", "fa", "he", "ur")

    /** Alte Android-Codes auf die heutigen umstellen */
    private fun normalize(c: String): String = when (c) {
        "iw" -> "he"
        "in" -> "id"
        else -> c
    }

    fun code(ctx: Context): String {
        val chosen = Prefs.lang(ctx)
        val c = normalize(if (chosen == Prefs.LANG_AUTO) Locale.getDefault().language else chosen)
        return if (LANGS.any { it.first == c }) c else "en"
    }

    fun isRtl(ctx: Context): Boolean = code(ctx) in RTL

    fun of(ctx: Context): Strings = when (code(ctx)) {
        "de" -> STR_DE
        "id" -> STR_ID
        "bs" -> STR_BS
        "es" -> STR_ES
        "fr" -> STR_FR
        "hr" -> STR_HR
        "it" -> STR_IT
        "sw" -> STR_SW
        "ku" -> STR_KU
        "hu" -> STR_HU
        "nl" -> STR_NL
        "pl" -> STR_PL
        "pt" -> STR_PT
        "ro" -> STR_RO
        "sq" -> STR_SQ
        "sk" -> STR_SK
        "sr" -> STR_SR
        "sv" -> STR_SV
        "vi" -> STR_VI
        "tr" -> STR_TR
        "cs" -> STR_CS
        "el" -> STR_EL
        "bg" -> STR_BG
        "ce" -> STR_CE
        "ru" -> STR_RU
        "uk" -> STR_UK
        "he" -> STR_HE
        "ur" -> STR_UR
        "ar" -> STR_AR
        "fa" -> STR_FA
        "hi" -> STR_HI
        "bn" -> STR_BN
        "th" -> STR_TH
        "zh" -> STR_ZH
        "ja" -> STR_JA
        "ko" -> STR_KO
        else -> STR_EN
    }
}
