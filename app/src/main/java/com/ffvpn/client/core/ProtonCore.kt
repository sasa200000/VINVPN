package com.ffvpn.client.core

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.ffvpn.client.core.proton.AwgHostClient
import com.ffvpn.client.core.proton.AwgHostService
import com.ffvpn.client.core.proton.ProtonApi
import kotlinx.coroutines.runBlocking
import java.net.Proxy

/**
 * Proton VPN over the account the user signed in to inside the app.
 *
 * The account half ([ProtonApi], the sign-in WebView) runs here; the tunnel
 * half is AmneziaWG in the `:awg` process ([AwgHostService]) — its Go runtime
 * must never load next to this process's, and the library's own VpnService
 * lives with it, so when it establishes, it takes the device TUN away from the
 * service's own interface. That is why this core is [directTunnel]: it
 * publishes no local SOCKS, the service's SOCKS probe has nothing to dial, and
 * [verifyTraffic] answers from the tunnel's own handshake and bytes instead.
 *
 * Sign-in state lives in the WebView's cookies (the password never reaches the
 * app); what the account gave is kept in [ProtonStore].
 */
class ProtonCore : VpnCore {
    override val id = "proton"

    /** No listener of its own; the service verifies through [verifyTraffic]. */
    override val proxyAddress: String get() = ""
    override val directTunnel: Boolean get() = true

    /**
     * Another attempt cannot help until the person acts: no session (the
     * cookie read is a cache — [connect] refreshes it on its worker, and until
     * it has, "blocked" stays off so a first failure takes the retry ladder's
     * own course), or a session whose servers were never taken — that has to
     * happen while a route reaches Proton, and at connect time there is none.
     */
    override val blockedOnUser: Boolean
        get() {
            if (!sessionChecked) return false
            if (!sessionPresent) return true
            val ctx = context ?: return false
            return runCatching { ProtonStore(ctx).confs().isEmpty() }.getOrDefault(false)
        }

    @Volatile
    private var sessionChecked = false

    @Volatile
    private var sessionPresent = false

    @Volatile
    private var context: Context? = null

    @Volatile
    private var listener: ((Int, String, String) -> Unit)? = null

    /** The config the next [startVpn] runs, chosen in [connect]. */
    @Volatile
    private var conf: String? = null

    @Volatile
    private var started = false

    override fun init(context: Context) {
        this.context = context.applicationContext
        // A host process that dies under a running tunnel ends the claim to be up.
        AwgHostClient.tunnel.onDied = {
            started = false
            AppState.log("engine proton: tunnel host process died")
        }
        AppState.log("engine proton: registered (sign-in state is read on the connect worker)")
    }

    override fun setSocketProtector(protector: ((Int) -> Boolean)?) = Unit

    override fun setStatusListener(listener: ((Int, String, String) -> Unit)?) {
        this.listener = listener
    }

    private val signedIn: Boolean
        get() = runCatching { ProtonApi.signedIn() }.getOrDefault(false)

    override fun connect(): String? {
        if (context == null) return Texts.engineMissing
        // The tunnel is a device VPN; a local proxy session has nothing to hand one.
        if (Prefs.mode == AppState.Mode.PROXY) return Texts.protonVpnOnly
        sessionPresent = signedIn
        sessionChecked = true
        if (!sessionPresent) return Texts.protonSignInNeeded

        val store = ProtonStore(context!!)
        if (store.confs().isEmpty()) {
            // Nothing saved yet. At this point no core is up — the service
            // stopped the old one before asking this one — so a fetch here
            // goes straight out, and on a filtered line the resolver answers
            // with a sinkhole (10.x) that refuses the connection. Only fetch
            // when the line itself reaches Proton; otherwise «دریافت سرورها»
            // in Settings, taken while a route is up, is the way.
            val direct = runCatching {
                runBlocking { com.ffvpn.client.core.proton.ProtonAccess.reachable(null) }
            }.getOrDefault(false)
            if (!direct) {
                AppState.log("engine proton: no route to Proton and no saved servers — not fetching")
                return Texts.protonNeedFetch
            }
            emit(1002, Texts.protonFetching, "")
            val out = runCatching { runBlocking { store.fetchAndSave(Proxy.NO_PROXY) } }.getOrNull()
            when (out) {
                is ProtonApi.Outcome.NotSignedIn, is ProtonApi.Outcome.Expired ->
                    return Texts.protonSignInNeeded
                is ProtonApi.Outcome.Failed -> {
                    AppState.log("engine proton: fetch failed: ${out.detail}")
                    return Texts.protonNoServers
                }
                else -> {}
            }
            if (store.confs().isEmpty()) {
                AppState.log("engine proton: fetch brought no servers (${out ?: "error"})")
                return Texts.protonNoServers
            }
        }

        val pick = store.next() ?: return Texts.protonNoServers
        conf = pick.text
        val where = pick.country ?: pick.name
        AppState.log("engine proton: next server $where")
        emit(1002, "اتصال به $where", "")
        return null
    }

    override fun disconnect() {
        conf = null
        stopVpn()
    }

    override fun startVpn(fd: Int): String? {
        val ctx = context ?: return Texts.engineMissing
        val config = conf ?: return Texts.tunnelFailed
        // fd unused on purpose: the library's VpnService in :awg establishes the
        // device TUN itself and takes the interface over, so the descriptor this
        // service holds goes stale — closeTunnel() closes it later, harmlessly.
        val reply = runCatching {
            AwgHostClient.tunnel.call(ctx, AwgHostService.MSG_START, Bundle().apply {
                putString(AwgHostService.KEY_CONFIG, config)
            }, START_TIMEOUT_MS)
        }.getOrNull()
        if (reply?.getBoolean(AwgHostService.KEY_OK) != true) {
            val error = reply?.getString(AwgHostService.KEY_ERROR)
                ?: "no answer from the tunnel host"
            runCatching { AwgHostClient.tunnel.release(ctx) }
            AppState.log("engine proton start: $error")
            return error
        }
        started = true
        AppState.log("engine proton: tunnel host reports UP")
        return null
    }

    override fun stopVpn() {
        val ctx = context
        val was = started
        started = false
        if (ctx == null || !was) return
        runCatching {
            AwgHostClient.tunnel.call(ctx, AwgHostService.MSG_STOP, null, STOP_TIMEOUT_MS)
        }
        runCatching { AwgHostClient.tunnel.release(ctx) }
    }

    /**
     * No local SOCKS to probe, so the tunnel proves itself: the host's stats
     * show a completed handshake and bytes back from the Proton server — data
     * that only exists when the server answered the key exchange through this
     * very tunnel. Both checks have to pass, and the poll is bounded so a
     * handshake that never comes fails the connect instead of hanging it.
     */
    override fun verifyTraffic(cancelled: () -> Boolean): Reachability.Outcome {
        val ctx = context ?: return Reachability.Outcome(false, "no context")
        var last = "not started"
        val deadline = SystemClock.elapsedRealtime() + VERIFY_BUDGET_MS
        while (!cancelled() && SystemClock.elapsedRealtime() < deadline) {
            val stats = runCatching { AwgHostClient.stats(ctx) }.getOrNull()
            when {
                stats == null -> last = "no answer from the tunnel host"
                !stats.up -> last = "tunnel is down"
                stats.handshakeAt <= 0L -> last = "no handshake with the server yet"
                stats.rx <= 0L -> last = "no data back from the server yet"
                else -> {
                    AppState.log("engine proton: handshake ok, ${stats.rx} rx bytes — carries traffic")
                    return Reachability.Outcome(true, "")
                }
            }
            runCatching { Thread.sleep(1_500L) }
        }
        AppState.log("engine proton: not proven in time: $last")
        return Reachability.Outcome(false, last)
    }

    override fun isRunning(): Boolean = started

    override fun isVpnRunning(): Boolean = started

    private fun emit(code: Int, message: String, data: String) {
        listener?.invoke(code, message, data)
    }

    private companion object {
        /** The host binds within 5 s; its start does the handshake setup and DNS. */
        const val START_TIMEOUT_MS = 25_000L
        const val STOP_TIMEOUT_MS = 4_000L

        /** A handshake on a working line lands inside 10 s; give it 30. */
        const val VERIFY_BUDGET_MS = 30_000L
    }
}
