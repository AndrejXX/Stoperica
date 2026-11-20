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
    private var ttsLanguageLabel = "Učitavam glas..."

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var startBaseRealtime = 0L
    private var accumulatedMs = 0L
    private var lastAnnouncedMinute = 0L
    private var keepScreenOn = true

    private var wakeLock: PowerManager.WakeLock? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            val elapsed = getCurrentElapsedMs()
            val currentMinute = elapsed / 60_000L

            if (currentMinute > lastAnnouncedMinute) {
                lastAnnouncedMinute = currentMinute
                val phrase = formatMinutePhrase(currentMinute)
                speakPhrase(phrase)
                callJs("window.onMinuteAnnounced($currentMinute, ${JSONObject.quote(phrase)})")
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
            val engine = tts ?: return
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            val candidates = listOf(
                Locale("hr", "HR") to "Hrvatski",
                Locale("bs", "BA") to "Bosanski",
                Locale("sr", "RS") to "Srpski",
                Locale.getDefault() to Locale.getDefault().displayLanguage
            )

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
                ttsLanguageLabel = "Glas spreman ($selectedLabel)"
                callJs("window.onTtsStatus(true, ${JSONObject.quote(ttsLanguageLabel)})")
            } else {
                ttsReady = false
                ttsLanguageLabel = "Glasovni paket nije instaliran"
                callJs("window.onTtsStatus(false, ${JSONObject.quote(ttsLanguageLabel)})")
            }
        } else {
            ttsReady = false
            ttsLanguageLabel = "Glasovna sinteza nije dostupna"
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

    private fun formatMinutePhrase(mins: Long): String {
        val mod100 = (mins % 100).toInt()
        val mod10 = (mins % 10).toInt()
        return when {
            mod100 in 11..14 -> "Prošlo je $mins minuta"
            mod10 == 1 -> "Prošla je $mins minuta"
            mod10 in 2..4 -> "Prošle su $mins minute"
            else -> "Prošlo je $mins minuta"
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
                lastAnnouncedMinute = 0L
                releaseWakeLock()
                tts?.stop()
                callJs("window.onTimerTick(0, false)")
            }
        }

        @JavascriptInterface
        fun testVoice() {
            handler.post {
                speakPhrase("Glasovna najava radi. Prošla je 1 minuta.")
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
                callJs("window.onTimerTick($elapsed, $isRunning)")
                callJs("window.onTtsStatus($ttsReady, ${JSONObject.quote(ttsLanguageLabel)})")
            }
        }
    }
}
