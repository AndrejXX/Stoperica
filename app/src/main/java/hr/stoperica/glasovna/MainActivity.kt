package hr.stoperica.glasovna

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitialized = false
    private var ttsLanguageLabel = "Učitavam glas..."

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var startBaseRealtime = 0L
    private var accumulatedMs = 0L
    private var lastAnnouncedStep = 0L
    private var intervalSec = 60
    private var currentLang = "hr"
    private var keepScreenOn = true

    private var wakeLock: PowerManager.WakeLock? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            val elapsed = getCurrentElapsedMs()
            val stepMs = intervalSec * 1000L
            val currentStep = elapsed / stepMs

            if (currentStep > lastAnnouncedStep) {
                lastAnnouncedStep = currentStep
                val totalSeconds = currentStep * intervalSec
                val phrase = formatAnnouncementPhrase(totalSeconds)
                speakPhrase(phrase)
                callJs("window.onTimeAnnounced($totalSeconds, ${JSONObject.quote(phrase)})")
            }

            callJs("window.onTimerTick($elapsed, $isRunning)")

            if (isRunning) {
                handler.postDelayed(this, 50L)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tts = TextToSpeech(this, this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(StopwatchBridge(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsInitialized = true
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            applyTtsLanguage()
        } else {
            ttsInitialized = false
            ttsReady = false
            ttsLanguageLabel = if (currentLang == "en") {
                "Speech engine unavailable"
            } else {
                "Glasovna sinteza nije dostupna"
            }
            callJs("window.onTtsStatus(false, ${JSONObject.quote(ttsLanguageLabel)})")
        }
    }

    private fun applyTtsLanguage() {
        val engine = tts ?: return
        if (!ttsInitialized) return

        val candidates = if (currentLang == "en") {
            listOf(
                Locale.UK to "English (UK)",
                Locale.US to "English (US)",
                Locale.ENGLISH to "English"
            )
        } else {
            listOf(
                Locale("hr", "HR") to "Hrvatski",
                Locale("bs", "BA") to "Bosanski",
                Locale("sr", "RS") to "Srpski"
            )
        }

        var selectedLabel = ""
        for ((loc, label) in candidates) {
            val res = engine.setLanguage(loc)
            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                selectedLabel = label
                break
            }
        }

        if (selectedLabel.isNotEmpty()) {
            ttsReady = true
            ttsLanguageLabel = if (currentLang == "en") {
                "Voice ready ($selectedLabel)"
            } else {
                "Glas spreman ($selectedLabel)"
            }
            callJs("window.onTtsStatus(true, ${JSONObject.quote(ttsLanguageLabel)})")
        } else {
            ttsReady = false
            ttsLanguageLabel = if (currentLang == "en") {
                "Voice pack not installed"
            } else {
                "Glasovni paket nije instaliran"
            }
            callJs("window.onTtsStatus(false, ${JSONObject.quote(ttsLanguageLabel)})")
        }
    }

    private fun getCurrentElapsedMs(): Long {
        return if (isRunning) {
            accumulatedMs + (SystemClock.elapsedRealtime() - startBaseRealtime)
        } else {
            accumulatedMs
        }
    }

    private fun formatAnnouncementPhrase(totalSeconds: Long): String {
        return if (currentLang == "en") {
            formatPhraseEn(totalSeconds)
        } else {
            formatPhraseHr(totalSeconds)
        }
    }

    private fun formatPhraseHr(totalSeconds: Long): String {
        val mins = totalSeconds / 60L
        val secs = totalSeconds % 60L

        if (mins == 0L && secs == 30L) {
            return "Prošlo je pola minute"
        }

        val mod100 = (mins % 100).toInt()
        val mod10 = (mins % 10).toInt()
        val minPart = when {
            mod100 in 11..14 -> "Prošlo je $mins minuta"
            mod10 == 1 -> "Prošla je $mins minuta"
            mod10 in 2..4 -> "Prošle su $mins minute"
            else -> "Prošlo je $mins minuta"
        }

        return if (secs == 30L) {
            "$minPart i 30 sekundi"
        } else {
            minPart
        }
    }

    private fun formatPhraseEn(totalSeconds: Long): String {
        val mins = totalSeconds / 60L
        val secs = totalSeconds % 60L

        if (mins == 0L && secs == 30L) {
            return "Half a minute has passed"
        }

        if (secs == 30L) {
            val unit = if (mins == 1L) "minute" else "minutes"
            return "$mins $unit and 30 seconds have passed"
        }

        return if (mins == 1L) {
            "1 minute has passed"
        } else {
            "$mins minutes have passed"
        }
    }

    private fun speakPhrase(text: String) {
        if (!ttsReady) return
        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "stoperica_${SystemClock.elapsedRealtime()}"
        )
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stoperica::TimerLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }
    }

    private fun callJs(script: String) {
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        releaseWakeLock()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    inner class StopwatchBridge {

        @JavascriptInterface
        fun start() {
            handler.post {
                if (isRunning) return@post
                isRunning = true
                startBaseRealtime = SystemClock.elapsedRealtime()
                acquireWakeLock()
                handler.removeCallbacks(tickRunnable)
                handler.post(tickRunnable)
            }
        }

        @JavascriptInterface
        fun pause() {
            handler.post {
                if (!isRunning) return@post
                accumulatedMs += (SystemClock.elapsedRealtime() - startBaseRealtime)
                isRunning = false
                handler.removeCallbacks(tickRunnable)
                releaseWakeLock()
                callJs("window.onTimerTick($accumulatedMs, false)")
            }
        }

        @JavascriptInterface
        fun reset() {
            handler.post {
                isRunning = false
                handler.removeCallbacks(tickRunnable)
                accumulatedMs = 0L
                lastAnnouncedStep = 0L
                releaseWakeLock()
                tts?.stop()
                callJs("window.onTimerTick(0, false)")
            }
        }

        @JavascriptInterface
        fun setIntervalSeconds(sec: Int) {
            handler.post {
                intervalSec = if (sec == 30) 30 else 60
                lastAnnouncedStep = getCurrentElapsedMs() / (intervalSec * 1000L)
                callJs("window.onTimerTick(${getCurrentElapsedMs()}, $isRunning)")
            }
        }

        @JavascriptInterface
        fun setLanguage(langCode: String) {
            handler.post {
                currentLang = if (langCode == "en") "en" else "hr"
                applyTtsLanguage()
            }
        }

        @JavascriptInterface
        fun testVoice() {
            handler.post {
                val sample = if (currentLang == "en") {
                    if (intervalSec == 30) {
                        "Voice announcement is working. Half a minute has passed."
                    } else {
                        "Voice announcement is working. 1 minute has passed."
                    }
                } else {
                    if (intervalSec == 30) {
                        "Glasovna najava radi. Prošlo je pola minute."
                    } else {
                        "Glasovna najava radi. Prošla je 1 minuta."
                    }
                }
                speakPhrase(sample)
            }
        }

        @JavascriptInterface
        fun setKeepScreenOn(enabled: Boolean) {
            handler.post {
                keepScreenOn = enabled
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        @JavascriptInterface
        fun syncState() {
            handler.post {
                val elapsed = getCurrentElapsedMs()
                callJs("window.onSettingsSync($intervalSec, ${JSONObject.quote(currentLang)})")
                callJs("window.onTimerTick($elapsed, $isRunning)")
                callJs("window.onTtsStatus($ttsReady, ${JSONObject.quote(ttsLanguageLabel)})")
            }
        }
    }
}
