package com.ffvpn.client.core.proton

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.CoreRegistry
import com.ffvpn.client.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * How the Proton sign-in page reaches Proton when Proton is filtered.
 *
 * Proton's site is blocked on Iranian lines, and the app's own WebView cannot
 * ride the tunnel the running core makes: the tunnels leave the app itself out
 * of the VPN (its sockets must not loop back into them), so a WebView that
 * simply opens the page goes direct and gets nothing. The way round is the
 * running core's local listener — every core that is up publishes one on
 * loopback — and the WebView is pointed at it, and only it, while the page is
 * open.
 *
 * So the user turns on any engine, signs in to Proton, takes the servers, and
 * the engine is turned off again before Proton itself is connected (Proton is a
 * WireGuard tunnel of its own and cannot run inside another one).
 *
 * Ported from mlmvpn_android (GPL-3.0), slimmed to this app's one SOCKS port.
 */
object ProtonAccess {

    /** A local listener that carries traffic through whatever core is up. */
    data class Route(val port: Int) {
        /** The rule the WebView's proxy override takes. */
        val rule: String get() = "socks5://127.0.0.1:$port"
        fun javaProxy(): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
    }

    /**
     * The listener of the core that is up and carrying traffic, or null: nothing
     * is on, one is still starting, or the core that is on has no listener (the
     * AmneziaWG tunnel Proton itself runs on is exactly that).
     */
    fun current(): Route? {
        val state = AppState.state
        if (!state.connected) return null
        val core = runCatching { CoreRegistry.get(state.coreId) }.getOrNull() ?: return null
        if (core.directTunnel) return null
        return Route(Prefs.socksPort)
    }

    /** True while a core is up or coming up, whether or not it has a listener. */
    fun anythingUp(): Boolean = AppState.state.running || AppState.state.connected

    /**
     * Whether Proton's sign-in site answers on this route. Any HTTP answer
     * counts, even a refusal: what a filter does is drop or reset the
     * connection, and that is what this tells apart.
     *
     * Through a core it is asked more patiently: a core that has just come up,
     * or a slow one, misses one short try often enough that a single 7 s try
     * sent users back to the engine list with the engine working.
     */
    suspend fun reachable(route: Route?): Boolean = withContext(Dispatchers.IO) {
        val tries = if (route != null) VIA_TRIES else 1
        val ms = if (route != null) VIA_MS else CHECK_MS
        repeat(tries) { i ->
            if (i > 0) delay(1_000)
            val ok = runCatching {
                val c = URL(CHECK_URL).openConnection(route?.javaProxy() ?: Proxy.NO_PROXY) as HttpURLConnection
                try {
                    c.connectTimeout = ms; c.readTimeout = ms
                    c.instanceFollowRedirects = false
                    c.responseCode in 100..599
                } finally {
                    c.disconnect()
                }
            }.getOrDefault(false)
            if (ok) return@withContext true
        }
        false
    }

    /**
     * Points the app's WebViews at [route], or back at the network when it is
     * null. The override is process-wide, so whoever sets it clears it when its
     * page closes. False when this WebView cannot do proxy overrides (a very
     * old one): the page then has to be opened in a browser that can.
     */
    suspend fun setWebViewProxy(route: Route?): Boolean = withContext(proxyDispatcher) {
        suspendCancellableCoroutine { c ->
            if (!proxyOverrideSupported()) {
                c.resume(route == null)
                return@suspendCancellableCoroutine
            }
            val now = Executor { it.run() }
            try {
                if (route == null) {
                    ProxyController.getInstance().clearProxyOverride(now) { if (c.isActive) c.resume(true) }
                } else {
                    ProxyController.getInstance()
                        .setProxyOverride(ProxyConfig.Builder().addProxyRule(route.rule).build(), now) {
                            if (c.isActive) c.resume(true)
                        }
                }
            } catch (e: Exception) {
                if (c.isActive) c.resume(false)
            }
        }
    }

    /** Puts the WebViews back to the network. Safe to call when no override was set. */
    fun clearWebViewProxy() {
        proxyThread.execute {
            if (!proxyOverrideSupported()) return@execute
            runCatching { ProxyController.getInstance().clearProxyOverride(Executor { it.run() }) { } }
        }
    }

    /**
     * False also on a phone with no WebView installed at all: asking loads the
     * WebView, which then throws an Error (ExceptionInInitializerError, «No
     * WebView installed»), and that closed the app from this thread.
     */
    private fun proxyOverrideSupported(): Boolean =
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        } catch (t: Throwable) {
            false
        }

    /**
     * Every proxy change on one thread of its own, in the order it was asked
     * for. On the main thread a change waited on the WebView's network thread —
     * and its first use loads the whole WebView — which froze the page and
     * ended in ANRs on opening and leaving it.
     */
    private val proxyThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "webview-proxy").apply { isDaemon = true }
    }
    private val proxyDispatcher by lazy { proxyThread.asCoroutineDispatcher() }

    private const val CHECK_URL = "https://account.protonvpn.com/"
    private const val CHECK_MS = 7_000
    private const val VIA_MS = 20_000
    private const val VIA_TRIES = 3
}
