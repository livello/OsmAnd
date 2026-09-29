package net.osmand.plus.plugins.voicegps

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import net.osmand.plus.R
import java.util.Locale

object VoiceGpsOfflineSpeechHelper {

	data class MissingLocale(val tag: String, val displayName: String)

	fun requiredLocales(): List<Locale> {
		val locales = LinkedHashSet<Locale>()
		locales.add(Locale.forLanguageTag("ru-RU"))
		val system = Locale.getDefault()
		if (!system.language.equals("ru", ignoreCase = true)) {
			locales.add(system)
		}
		return locales.toList()
	}

	fun missingOfflineLocales(context: Context): List<MissingLocale> {
		if (!SpeechRecognizer.isRecognitionAvailable(context)) {
			return requiredLocales().map {
				MissingLocale(it.toLanguageTag(), it.displayName)
			}
		}
		val missing = ArrayList<MissingLocale>()
		for (locale in requiredLocales()) {
			if (!hasOfflineModel(context, locale)) {
				missing.add(MissingLocale(locale.toLanguageTag(), locale.displayName))
			}
		}
		return missing
	}

	private fun hasOfflineModel(context: Context, locale: Locale): Boolean {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			return true
		}
		return try {
			val intent = baseRecognizerIntent(locale)
			val method = SpeechRecognizer::class.java.getMethod(
				"checkRecognitionSupport",
				Context::class.java,
				Intent::class.java
			)
			val flags = method.invoke(null, context, intent) as Int
			flags and OFFLINE_SUPPORT_FLAG != 0
		} catch (_: Exception) {
			true
		}
	}

	private const val OFFLINE_SUPPORT_FLAG = 2

	private fun baseRecognizerIntent(locale: Locale): Intent {
		return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
			putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
			putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
			putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
		}
	}

	fun openOfflineSpeechDownloadSettings(context: Context) {
		val intents = listOf(
			Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
			Intent("android.settings.VOICE_INPUT_SETTINGS"),
			Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
		)
		for (intent in intents) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			if (intent.resolveActivity(context.packageManager) != null) {
				context.startActivity(intent)
				return
			}
		}
	}

	fun missingLocalesMessage(context: Context, missing: List<MissingLocale>): String {
		val names = missing.joinToString("\n") { "• ${it.displayName} (${it.tag})" }
		return context.getString(R.string.voice_gps_offline_stt_missing_message, names)
	}
}
