package net.osmand.plus.plugins.torrentmaps

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.settings.enums.ThemeUsageContext
import net.osmand.plus.utils.AndroidUtils
import java.util.Locale

/**
 * In-app WebView so the user can log into RuTracker / pass Cloudflare,
 * then we capture cookies via [CookieManager] into [TorrentMapsPlugin.TORRENT_RUTRACKER_COOKIE].
 *
 * Cloudflare Turnstile loops ("Success" then the checkbox again) when the WebView
 * pretends to be a different Chrome than the engine, or when a stale `cf_clearance`
 * is restored. Keep the stock WebView UA (only `; wv` is stripped — CF blocks that
 * token) and never treat a guest `bb_session` as a completed login.
 */
class RutrackerAuthActivity : AppCompatActivity() {

	companion object {
		const val EXTRA_TOPIC_URL = "topic_url"
		private const val COOKIE_URL = "https://rutracker.org"
		private val COOKIE_HOSTS = arrayOf(
			"https://rutracker.org/",
			"https://www.rutracker.org/",
			"https://rutracker.net/",
			"https://challenges.cloudflare.com/"
		)
		private val CF_COOKIE_PREFIXES = arrayOf("cf_", "__cf", "_cfuvid", "__cfruid")
	}

	private val app: OsmandApplication
		get() = application as OsmandApplication

	private val plugin: TorrentMapsPlugin
		get() = PluginsHelper.requirePlugin(TorrentMapsPlugin::class.java)

	private lateinit var webView: WebView
	private lateinit var progress: ProgressBar
	private lateinit var hint: TextView
	private var finishing = false
	private var lastKind: PageKind = PageKind.OTHER
	private var topicUrl: String = "https://rutracker.org/forum/viewtopic.php?t=5233935"
	private var cfFinishCount = 0
	private var connectProxy: RutrackerConnectProxy? = null
	private var proxyApplied = false

	private enum class PageKind { CF, LOGIN, TOPIC, OTHER }

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?) {
		val night = app.daynightHelper.isNightMode(ThemeUsageContext.APP)
		setTheme(if (night) R.style.OsmandDarkTheme else R.style.OsmandLightTheme)
		super.onCreate(savedInstanceState)
		CookieManager.getInstance().setAcceptCookie(true)
		setContentView(R.layout.torrent_maps_rutracker_auth)

		webView = findViewById(R.id.rutracker_webview)
		progress = findViewById(R.id.rutracker_progress)
		hint = findViewById(R.id.rutracker_hint)

		topicUrl = intent.getStringExtra(EXTRA_TOPIC_URL)
			?.takeIf { it.isNotBlank() }
			?: plugin.TORRENT_TOPIC_URL.get().orEmpty().ifBlank { topicUrl }

		findViewById<android.view.View>(R.id.rutracker_close).setOnClickListener { finish() }
		findViewById<android.view.View>(R.id.rutracker_done).setOnClickListener {
			saveCookiesAndFinish(force = true)
		}
		findViewById<android.view.View>(R.id.rutracker_open_browser).setOnClickListener {
			AndroidUtils.openUrl(this, topicUrl, night)
		}

		val cookieManager = CookieManager.getInstance()
		cookieManager.setAcceptCookie(true)
		cookieManager.setAcceptThirdPartyCookies(webView, true)
		dropCloudflareCookies(cookieManager)
		restoreLoginCookies(cookieManager)

		val settings = webView.settings
		settings.javaScriptEnabled = true
		settings.domStorageEnabled = true
		settings.databaseEnabled = true
		settings.javaScriptCanOpenWindowsAutomatically = true
		settings.setSupportMultipleWindows(false)
		settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
		settings.useWideViewPort = true
		settings.loadWithOverviewMode = true
		settings.allowFileAccess = true
		settings.allowContentAccess = true
		settings.cacheMode = WebSettings.LOAD_DEFAULT
		if (Build.VERSION.SDK_INT >= 26) {
			settings.safeBrowsingEnabled = false
		}
		val xrwOff = try {
			suppressRequestedWithHeader(settings)
		} catch (_: Throwable) {
			false
		}
		val ua = stripWebViewToken(settings.userAgentString)
		if (ua != settings.userAgentString) {
			settings.userAgentString = ua
		}

		val pkg = if (Build.VERSION.SDK_INT >= 26) {
			try {
				WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
			} catch (_: Exception) {
				null
			}
		} else {
			null
		}
		TorrentMapsLog.append("RuTracker WebView pkg=$pkg xrwOff=$xrwOff ua=${settings.userAgentString}")

		webView.webChromeClient = object : WebChromeClient() {
			override fun onProgressChanged(view: WebView?, newProgress: Int) {
				progress.isVisible = newProgress in 1..99
				progress.progress = newProgress
			}

			override fun onReceivedTitle(view: WebView?, title: String?) {
				if (isCloudflareTitle(title)) {
					lastKind = PageKind.CF
					hint.setText(cfHintRes())
				}
			}
		}
		webView.webViewClient = object : WebViewClient() {
			@Deprecated("Deprecated in Java")
			override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
				return handleUrl(url, forMainFrame = true)
			}

			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
				if (!request.isForMainFrame) {
					return false
				}
				return handleUrl(request.url?.toString().orEmpty(), forMainFrame = true)
			}

			override fun onReceivedError(
				view: WebView,
				request: WebResourceRequest,
				error: WebResourceError
			) {
				if (!request.isForMainFrame) return
				val desc = error.description?.toString().orEmpty()
				TorrentMapsLog.append("WebView error ${error.errorCode} $desc ${request.url}")
				hint.text = getString(R.string.torrent_maps_rutracker_auth_load_fail, desc)
			}

			@Deprecated("Deprecated in Java")
			override fun onReceivedError(
				view: WebView?,
				errorCode: Int,
				description: String?,
				failingUrl: String?
			) {
				TorrentMapsLog.append("WebView error $errorCode $description $failingUrl")
				hint.text = getString(
					R.string.torrent_maps_rutracker_auth_load_fail,
					description.orEmpty()
				)
			}

			override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
				val kind = classify(url, null)
				lastKind = kind
				if (kind == PageKind.CF) {
					hint.setText(cfHintRes())
				} else {
					hint.setText(
						when (kind) {
							PageKind.LOGIN -> R.string.torrent_maps_rutracker_auth_login
							else -> R.string.torrent_maps_rutracker_auth_hint
						}
					)
				}
			}

			override fun onPageFinished(view: WebView?, url: String?) {
				val kind = classify(url, view?.title)
				lastKind = kind
				when (kind) {
					PageKind.CF -> {
						cfFinishCount++
						hint.setText(cfHintRes())
					}
					PageKind.LOGIN -> {
						cfFinishCount = 0
						hint.setText(R.string.torrent_maps_rutracker_auth_login)
					}
					PageKind.TOPIC -> {
						cfFinishCount = 0
						hint.setText(R.string.torrent_maps_rutracker_auth_topic_ready)
						persistCookies()
					}
					PageKind.OTHER -> persistCookies()
				}
			}
		}

		loadTopicPreferringIpv6()
	}

	override fun onBackPressed() {
		if (this::webView.isInitialized && webView.canGoBack()) {
			webView.goBack()
		} else {
			super.onBackPressed()
		}
	}

	override fun onDestroy() {
		clearConnectProxy()
		if (this::webView.isInitialized) {
			try {
				CookieManager.getInstance().flush()
			} catch (_: Exception) {
			}
			webView.stopLoading()
			(webView.parent as? android.view.ViewGroup)?.removeView(webView)
			webView.destroy()
		}
		super.onDestroy()
	}

	private fun loadTopicPreferringIpv6() {
		val loaded = java.util.concurrent.atomic.AtomicBoolean(false)
		val doLoad = Runnable {
			if (loaded.compareAndSet(false, true) && !isFinishing && this::webView.isInitialized) {
				webView.loadUrl(topicUrl)
			}
		}
		try {
			if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
				val proxy = RutrackerConnectProxy()
				val port = proxy.start()
				connectProxy = proxy
				val config = ProxyConfig.Builder()
					.addProxyRule("127.0.0.1:$port")
					.addBypassRule("<-loopback>")
					.build()
				ProxyController.getInstance().setProxyOverride(config, { it.run() }) {
					proxyApplied = true
					TorrentMapsLog.append("RuTracker IPv6 proxy 127.0.0.1:$port")
					runOnUiThread(doLoad)
				}
				webView.postDelayed(doLoad, 2000)
				return
			}
		} catch (e: Throwable) {
			TorrentMapsLog.append("RuTracker proxy skip: ${e.message}")
			connectProxy?.stop()
			connectProxy = null
		}
		doLoad.run()
	}

	private fun clearConnectProxy() {
		if (proxyApplied) {
			try {
				if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
					ProxyController.getInstance().clearProxyOverride({ it.run() }) {}
				}
			} catch (_: Throwable) {
			}
			proxyApplied = false
		}
		connectProxy?.stop()
		connectProxy = null
	}

	private fun cfHintRes(): Int {
		return if (cfFinishCount >= 3) {
			R.string.torrent_maps_rutracker_auth_cf_loop
		} else {
			R.string.torrent_maps_rutracker_auth_cf
		}
	}

	private fun handleUrl(url: String, forMainFrame: Boolean): Boolean {
		if (!forMainFrame || url.isBlank()) return false
		val lower = url.lowercase(Locale.US)
		if (lower.startsWith("about:") || lower.startsWith("javascript:")) {
			return false
		}
		return lower.startsWith("magnet:")
	}

	private fun classify(url: String?, title: String?): PageKind {
		val u = url.orEmpty().lowercase(Locale.US)
		if (isCloudflareTitle(title) ||
			u.contains("cdn-cgi") ||
			u.contains("challenge") ||
			(u.contains("cf-") && u.contains("chl"))
		) {
			return PageKind.CF
		}
		if (u.contains("login.php") || (u.contains("/login") && u.contains("rutracker"))) {
			return PageKind.LOGIN
		}
		if (u.contains("rutracker.") &&
			(u.contains("viewtopic.php") || u.contains("/forum/viewtopic"))
		) {
			return PageKind.TOPIC
		}
		return PageKind.OTHER
	}

	private fun isCloudflareTitle(title: String?): Boolean {
		val t = title.orEmpty().lowercase(Locale.US)
		return t.contains("just a moment") ||
			t.contains("attention required") ||
			t.contains("checking your browser") ||
			t.contains("выполняется проверка") ||
			(t.contains("подождите") && t.contains("cloudflare"))
	}

	private fun saveCookiesAndFinish(force: Boolean) {
		if (finishing) return
		if (lastKind == PageKind.CF && !force) {
			return
		}
		val raw = currentCookieHeader()
		if (raw.isNullOrBlank()) {
			if (force) {
				app.showToastMessage(R.string.torrent_maps_rutracker_auth_no_cookie)
			}
			return
		}
		plugin.TORRENT_RUTRACKER_COOKIE.set(raw)
		try {
			CookieManager.getInstance().flush()
		} catch (_: Exception) {
		}
		setResult(RESULT_OK)
		if (force) {
			finishing = true
			app.showToastMessage(R.string.torrent_maps_rutracker_auth_saved)
			finish()
		}
	}

	private fun persistCookies() {
		val raw = currentCookieHeader() ?: return
		plugin.TORRENT_RUTRACKER_COOKIE.set(raw)
	}

	private fun currentCookieHeader(): String? {
		val cm = CookieManager.getInstance()
		return COOKIE_HOSTS.firstNotNullOfOrNull { host ->
			if (host.contains("cloudflare")) null
			else cm.getCookie(host)?.trim()?.takeIf { it.isNotBlank() }
		} ?: cm.getCookie(COOKIE_URL)?.trim()?.takeIf { it.isNotBlank() }
	}

	private fun restoreLoginCookies(cm: CookieManager) {
		val raw = plugin.TORRENT_RUTRACKER_COOKIE.get().orEmpty().trim()
		if (raw.isBlank()) return
		for (part in raw.split(';')) {
			val cookie = part.trim()
			if (cookie.isEmpty() || !cookie.contains('=')) continue
			val name = cookie.substringBefore('=').trim()
			if (isCloudflareCookieName(name)) continue
			for (host in COOKIE_HOSTS) {
				if (host.contains("cloudflare")) continue
				try {
					cm.setCookie(host, cookie)
				} catch (_: Exception) {
				}
			}
		}
		try {
			cm.flush()
		} catch (_: Exception) {
		}
	}

	private fun dropCloudflareCookies(cm: CookieManager) {
		for (host in COOKIE_HOSTS) {
			val raw = try {
				cm.getCookie(host)
			} catch (_: Exception) {
				null
			} ?: continue
			for (part in raw.split(';')) {
				val name = part.substringBefore('=').trim()
				if (!isCloudflareCookieName(name)) continue
				try {
					cm.setCookie(host, "$name=; Max-Age=0; Path=/")
					cm.setCookie(host, "$name=; Max-Age=0; Path=/; Domain=.rutracker.org")
				} catch (_: Exception) {
				}
			}
		}
		try {
			cm.flush()
		} catch (_: Exception) {
		}
	}

	private fun isCloudflareCookieName(name: String): Boolean {
		val n = name.lowercase(Locale.US)
		return CF_COOKIE_PREFIXES.any { n.startsWith(it) }
	}

	/**
	 * Keep the engine's own UA so Client Hints match. Only drop `; wv`, which
	 * Cloudflare uses as a hard WebView block.
	 */
	private fun stripWebViewToken(defaultUa: String): String {
		return defaultUa.replace("; wv", "").replace(";wv", "")
	}

	/**
	 * WebView always sends `X-Requested-With: <package>`. Cloudflare treats that
	 * as a bot after Turnstile reports success. Official API needs a recent
	 * WebView; old engines (Chrome 96) throw on unknown feature names.
	 */
	private fun suppressRequestedWithHeader(settings: WebSettings): Boolean {
		try {
			if (WebViewFeature.isFeatureSupported("REQUESTED_WITH_HEADER_ALLOW_LIST")) {
				WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
				return true
			}
		} catch (_: Throwable) {
		}
		var cls: Class<*>? = settings.javaClass
		while (cls != null) {
			try {
				val allowList = cls.getMethod("setRequestedWithHeaderOriginAllowList", Set::class.java)
				allowList.isAccessible = true
				allowList.invoke(settings, emptySet<String>())
				return true
			} catch (_: Throwable) {
			}
			try {
				val mode = cls.getDeclaredMethod("setRequestedWithHeaderMode", Int::class.javaPrimitiveType)
				mode.isAccessible = true
				mode.invoke(settings, 0)
				return true
			} catch (_: Throwable) {
			}
			cls = cls.superclass
		}
		return false
	}
}
