package kg.bishkek.markxobd

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Голосовые предупреждения. Если русский голос недоступен — звуковой сигнал. */
class Voice(context: Context) : TextToSpeech.OnInitListener {
    private val tts: TextToSpeech? = try {
        TextToSpeech(context.applicationContext, this)
    } catch (_: Exception) {
        null
    }

    @Volatile
    var ready = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        val r = t.setLanguage(Locale("ru", "RU"))
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        try {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        } catch (_: Exception) {
        }
    }

    /** urgent = true — перебить то, что говорится сейчас. */
    fun say(text: String, urgent: Boolean = false) {
        val t = tts
        if (t == null || !ready) {
            beep(urgent)
            return
        }
        try {
            t.speak(
                text,
                if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                Bundle(),
                "obd-${System.nanoTime()}",
            )
        } catch (_: Exception) {
            beep(urgent)
        }
    }

    fun beep(long: Boolean) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, if (long) 1500 else 500)
            Thread {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                }
                tg.release()
            }.start()
        } catch (_: Exception) {
        }
    }
}
