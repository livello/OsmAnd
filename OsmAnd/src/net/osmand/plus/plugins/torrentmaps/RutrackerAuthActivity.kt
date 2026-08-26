package net.osmand.plus.plugins.torrentmaps

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.enums.ThemeUsageContext

/**
 * In-app WebView so the user can log into RuTracker / pass Cloudflare,
 * then we capture cookies via [CookieManager] into [TorrentMapsPlugin.TORRENT_RUTRACKER_COOKIE].
 */
class RutrackerAuthActivity : AppCompatActivity() {

	companion object {
		const val EXTRA_TOPIC_URL = "topic_url"
		private const val COOKIE_URL = "https://rutracker.org"
	}

	private val app: OsmandApplication
		get() = application as OsmandApplication

	private val plugin: TorrentMapsPlugin
		get() = PluginsHelper.requirePlugin(TorrentMapsPlugin::class.java)

	private lateinit var webView: WebView
	private lateinit var progress: ProgressBar
	private lateinit var hint: TextView
	private var cookiesSaved = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?) {
		val night = app.daynightHelper.isNightMode(ThemeUsageContext.APP)
		setTheme(if (night) R.style.OsmandDarkTheme else R.style.OsmandLightTheme)
		super.onCreate(savedInstanceState)
		setContentView(R.layout.torrent_maps_rutracker_auth)

		webView = findViewById(R.id.rutracker_webview)
		progress = findViewById(R.id.rutracker_progress)
		hint = findViewById(R.id.rutracker_hint)

		findViewById<android.view.View>(R.id.rutracker_close).setOnClickListener { finish() }
		findViewById<android.view.View>(R.id.rutracker_done).setOnClickListener {
			saveCookiesAndFinish(force = true, finishIfLoggedIn = true)
		}

		val cookieManager = CookieManager.getInstance()
		cookieManager.setAcceptCookie(true)
		cookieManager.setAcceptThirdPartyCookies(webView, true)

		val settings = webView.settings
		settings.javaScriptEnabled = true
		settings.domStorageEnabled = true
		settings.databaseEnabled = true
		settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

		webView.webChromeClient = object : WebChromeClient() {
			override fun onProgressChanged(view: WebView?, newProgress: Int) {
				progress.isVisible = newProgress in 1..99
				progress.progress = newProgress
			}
		}
		webView.webViewClient = object : WebViewClient() {
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
				return false
			}

			override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
				hint.setText(R.string.torrent_maps_rutracker_auth_hint)
			}

			override fun onPageFinished(view: WebView?, url: String?) {
				if (url != null && looksLikeTopicPage(url)) {
					hint.setText(R.string.torrent_maps_rutracker_auth_topic_ready)
					// Persist cookies as soon as the topic is reachable; finish early only if logged in.
					saveCookiesAndFinish(force = false, finishIfLoggedIn = true)
				}
			}
		}

		val topic = intent.getStringExtra(EXTRA_TOPIC_URL)
			?.takeIf { it.isNotBlank() }
			?: plugin.TORRENT_TOPIC_URL.get().orEmpty().ifBlank {
				"https://rutracker.org/forum/viewtopic.php?t=5233935"
			}
		webView.loadUrl(topic)
	}

	override fun onBackPressed() {
		if (this::webView.isInitialized && webView.canGoBack()) {
			webView.goBack()
		} else {
			super.onBackPressed()
		}
	}

	override fun onDestroy() {
		if (this::webView.isInitialized) {
			webView.stopLoading()
			webView.destroy()
		}
		super.onDestroy()
	}

	private fun looksLikeTopicPage(url: String): Boolean {
		val lower = url.lowercase()
		return lower.contains("rutracker.org") &&
				(lower.contains("viewtopic.php") || lower.contains("/forum/viewtopic"))
	}

	private fun saveCookiesAndFinish(force: Boolean, finishIfLoggedIn: Boolean) {
		val raw = CookieManager.getInstance().getCookie(COOKIE_URL)
			?: CookieManager.getInstance().getCookie("https://www.rutracker.org")
		if (raw.isNullOrBlank()) {
			if (force) {
				app.showToastMessage(R.string.torrent_maps_rutracker_auth_no_cookie)
			}
			return
		}
		val trimmed = raw.trim()
		val changed = plugin.TORRENT_RUTRACKER_COOKIE.get().orEmpty() != trimmed
		plugin.TORRENT_RUTRACKER_COOKIE.set(trimmed)
		CookieManager.getInstance().flush()
		setResult(RESULT_OK)
		val loggedIn = looksLoggedIn(trimmed)
		if (force || (finishIfLoggedIn && loggedIn)) {
			if (!cookiesSaved || changed) {
				app.showToastMessage(R.string.torrent_maps_rutracker_auth_saved)
			}
			cookiesSaved = true
			finish()
		} else if (changed && !cookiesSaved) {
			cookiesSaved = true
			// Soft save (e.g. Cloudflare clearance) — keep WebView open for login / Done.
			hint.setText(R.string.torrent_maps_rutracker_auth_topic_ready)
		}
	}

	private fun looksLoggedIn(cookie: String): Boolean {
		val lower = cookie.lowercase()
		return lower.contains("bb_session") || lower.contains("bb_userid") || lower.contains("bb_password")
	}
}
