package com.ffvpn.client.core

import android.content.Context
import android.os.SystemClock
import com.ffvpn.client.Texts
import com.multicore.vpn.tunnel.Tun2Socks
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cloudflare WARP over the bundled Aether binary's WireGuard mode.
 *
 * The same `libaether.so` [AetherCore] runs for MASQUE, told to speak WireGuard
 * instead through `AETHER_PROTOCOL=wg`. The data plane is WARP's obfuscated
 * WireGuard — the one measured to carry traffic on lines where plain WireGuard
 * carries nothing — and the identity is WARP's own, registered by the binary
 * itself against the WARP API.
 *
 * Registration is the part a filtered line breaks: `api.cloudflareclient.com`
 * is dropped by name in Iran, so a first run registers through ECH (the binary
 * looks up the ECH key of cloudflare-ech.com and hides the server name), rung
 * by rung, before falling back to the plain call. A tunnel never waits on a
 * registration the line cannot make — the engine provisions an identity itself
 * when the file is missing, and the connect budget covers that too.
 *
 * Identity lives in `aether.toml` ([IDENTITY_FILE]) — the file `AETHER_CONFIG`
 * names, which is where the engine reads `device_id` and `wg_private_key` from.
 */
class WarpCore : VpnCore {
    override val id = "warp"
    override val proxyAddress get() = Prefs.socksAddress

    @Volatile
    private var context: Context? = null

    @Volatile
    private var listener: ((Int, String, String) -> Unit)? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopping = true

    @Volatile
    private var vpnStarted = false

    @Volatile
    private var lastLine = ""

    @Volatile
    private var serving = false

    @Volatile
    private var connected = false

    override fun init(context: Context) {
        this.context = context.applicationContext
        workDir.mkdirs()
        AppState.log("engine $id binary=${binary.exists()} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
    }

    override fun setSocketProtector(protector: ((Int) -> Boolean)?) = Unit

    override fun setStatusListener(listener: ((Int, String, String) -> Unit)?) {
        this.listener = listener
    }

    override fun connect(): String? {
        val executable = binary
        if (!executable.exists()) return Texts.engineMissing
        workDir.mkdirs()
        stopping = false
        connected = false
        serving = false

        // The identity first: a line that filters the WARP API needs the ECH
        // ladder, which only this step runs. The engine would provision one
        // itself, but straight at the API's filtered name.
        if (!hasIdentity()) {
            emit(1002, "ساخت هویت WARP...", "")
            val made = registerIdentity()
            AppState.log("engine $id identity: " +
                (if (made) "registered" else "not made here — the engine will try"))
        }
        if (stopping) return null

        emit(1002, "اتصال به WARP...", "")
        val startedAt = SystemClock.elapsedRealtime()
        val child = try {
            ProcessBuilder(listOf(executable.absolutePath))
                .directory(workDir)
                .redirectErrorStream(true)
                .also { CaBundle.applyTo(it) }
                .apply {
                    val env = environment()
                    // Never leak a stale setting or a proxy the identity must not go through.
                    env.keys.filter {
                        it.startsWith("AETHER_") || it.endsWith("_proxy", ignoreCase = true)
                    }.forEach { env.remove(it) }
                    env.putAll(engineEnv())
                }
                .start()
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        }
        if (stopping) {
            destroyProcess(child)
            return null
        }
        process = child
        pumpLogs(child)

        while (!stopping) {
            if (serving) {
                connected = true
                AppState.log("engine $id tunnel up (${SystemClock.elapsedRealtime() - startedAt}ms)")
                emit(1004, "ready", "")
                return null
            }
            if (!child.isAlive) {
                val code = runCatching { child.exitValue() }.getOrNull()
                return "process exited ($code) ${lastLine.take(160)}".trim()
            }
            if (SystemClock.elapsedRealtime() - startedAt >= CONNECT_BUDGET_MS) {
                destroyProcess(child)
                return Texts.endpointNotFound
            }
            try {
                Thread.sleep(300L)
            } catch (_: InterruptedException) {
                break
            }
        }
        destroyProcess(child)
        return null
    }

    override fun disconnect() {
        stopping = true
        connected = false
        stopVpn()
        destroyProcess()
    }

    override fun startVpn(fd: Int): String? {
        val config = writeTunnelConfig()
        val started = try {
            Tun2Socks.TProxyStartService(config.absolutePath, fd)
        } catch (t: Throwable) {
            AppState.log("engine $id tunnel bridge: ${t.message ?: t.javaClass.simpleName}")
            false
        }
        if (!started) return Texts.tunnelFailed
        vpnStarted = true
        repeat(15) {
            Thread.sleep(100L)
            if (runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)) {
                AppState.log("engine $id tunnel bridge started")
                return null
            }
        }
        stopVpn()
        return Texts.tunnelFailed
    }

    override fun stopVpn() {
        if (!vpnStarted) return
        vpnStarted = false
        runCatching { Tun2Socks.TProxyStopService() }
            .onFailure { AppState.log("engine $id tunnel stop: ${it.message}") }
    }

    override fun isRunning(): Boolean {
        val alive = process?.isAlive == true
        return alive || (vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false))
    }

    override fun isVpnRunning(): Boolean =
        vpnStarted && runCatching { Tun2Socks.TProxyIsRunning() }.getOrDefault(false)

    // ── identity ──────────────────────────────────────────────────────────────

    private fun hasIdentity(): Boolean {
        val text = runCatching { File(workDir, IDENTITY_FILE).readText() }.getOrNull() ?: return false
        return IDENTITY_FIELDS.all { Regex(it).containsMatchIn(text) }
    }

    /**
     * One register run per rung of the ladder the desktop driver uses: ECH over
     * three resolvers, then the plain call. Each rung gets its own budget and a
     * clean file, so a half-written identity from a rung the line killed cannot
     * be mistaken for one the API made. Returns when a file the engine accepts
     * exists, the ladder is spent, or the session is stopped.
     */
    private fun registerIdentity(): Boolean {
        val config = File(workDir, IDENTITY_FILE).absolutePath
        val rungs = listOf(
            listOf("--ech", "auto", "--ech-dns", "udp://1.1.1.1"),
            listOf("--ech", "auto", "--ech-dns", "https://1.1.1.1/dns-query"),
            listOf("--ech", "auto", "--ech-dns", "tcp://8.8.8.8"),
            emptyList(),
        )
        val started = SystemClock.elapsedRealtime()
        for (rung in rungs) {
            if (stopping || Thread.currentThread().isInterrupted) return false
            if (SystemClock.elapsedRealtime() - started > REGISTER_TOTAL_MS) break
            File(workDir, IDENTITY_FILE).delete()
            val cmd = listOf(
                binary.absolutePath, "--register", "wg",
                "--config", config, "--wg-config", config,
                "--masque-config", File(workDir, "masque.toml").absolutePath,
                "--log-level", "info",
            ) + rung
            if (runRegister(cmd) && hasIdentity()) {
                AppState.log("engine $id identity made over " + (rung.lastOrNull() ?: "the plain call"))
                return true
            }
        }
        return hasIdentity()
    }

    /** One register attempt: bounded, drained, and gone by the time it returns. */
    private fun runRegister(cmd: List<String>): Boolean {
        val child = try {
            ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .also { CaBundle.applyTo(it) }
                .apply {
                    val env = environment()
                    env.keys.filter {
                        it.startsWith("AETHER_") || it.endsWith("_proxy", ignoreCase = true)
                    }.forEach { env.remove(it) }
                    env["RUST_LOG"] = "info"
                }
                .start()
        } catch (t: Throwable) {
            AppState.log("engine $id register start: ${t.message ?: t.javaClass.simpleName}")
            return false
        }
        // Drained so the binary never blocks on a full pipe; only the last line
        // is kept, for the reason of a failure (the rest names the device).
        var last = ""
        val reader = Thread({
            runCatching { child.inputStream.bufferedReader().forEachLine { last = it } }
        }, "warp-register-out").apply { isDaemon = true; start() }
        val deadline = SystemClock.elapsedRealtime() + REGISTER_ATTEMPT_MS
        return try {
            while (!child.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (stopping || Thread.currentThread().isInterrupted ||
                    SystemClock.elapsedRealtime() > deadline
                ) {
                    destroyProcess(child)
                    return false
                }
            }
            reader.join(500)
            val code = child.exitValue()
            if (code != 0) {
                AppState.log("engine $id register exited $code: ${last.takeLast(160)}")
            }
            code == 0
        } catch (t: Throwable) {
            destroyProcess(child)
            false
        } finally {
            if (child.isAlive) destroyProcess(child)
        }
    }

    // ── environment the engine reads ──────────────────────────────────────────

    /**
     * The env map of an `AETHER_PROTOCOL=wg` run, same shape the desktop
     * driver builds. IPv4 is resolved per network the way the reference client
     * does it: a device with a global IPv6 route scans both families, one
     * without scans v4 — on some lines Cloudflare's IPv4 carries nothing and on
     * some its v6 does not exist, so neither family alone is a choice.
     */
    private fun engineEnv(): Map<String, String> {
        val identity = File(workDir, IDENTITY_FILE).absolutePath
        return mapOf(
            "RUST_BACKTRACE" to "1",
            "RUST_LOG" to "info",
            "AETHER_SOCKS" to "127.0.0.1:${Prefs.socksPort}",
            "AETHER_PROTOCOL" to "wg",
            "AETHER_SCAN" to "balanced",
            "AETHER_IP" to if (hasGlobalIpv6()) "both" else "v4",
            "AETHER_QUICK_RECONNECT" to "1",
            "AETHER_CONFIG" to identity,
            // The WireGuard table has no "firewall": an empty value falls
            // through to balanced silently, so say it out loud.
            "AETHER_NOIZE" to "balanced",
            // The core declares the tunnel dead after ~12s without inbound
            // data, which an idle WireGuard (keepalives draw no reply) always
            // looks like: the tunnel was reconnecting every 12s and carrying
            // nothing. 25/120 are the desktop's figures and what loads pages.
            "AETHER_WG_KEEPALIVE" to "25",
            "AETHER_WG_STALE_SECS" to "120",
        )
    }

    private fun hasGlobalIpv6(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nif ->
            nif.inetAddresses?.toList().orEmpty().any { addr ->
                addr is java.net.Inet6Address &&
                    !addr.isLinkLocalAddress && !addr.isLoopbackAddress && !addr.isAnyLocalAddress
            }
        }
    }.getOrDefault(false)

    // ── log pump: stage text for the ring, the serving marker for the state ──

    private fun pumpLogs(child: Process) {
        EngineProc.attach(
            child,
            Thread {
                runCatching {
                    val reader = child.inputStream.bufferedReader()
                    while (true) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        lastLine = line.trim()
                        if (lastLine.contains(SERVING_MARKER, ignoreCase = true)) {
                            serving = true
                        } else {
                            stageOf(lastLine)?.let { emit(1002, it, "") }
                        }
                        AppState.log("engine $id: $lastLine")
                    }
                }
            }.apply {
                isDaemon = true
                name = "warp-log"
                start()
            }
        )
    }

    /** The engine's own lines, in Persian, for the ring the user watches. */
    private fun stageOf(line: String): String? = when {
        Regex("""no (warp|masque) identity found|provisioning""", RegexOption.IGNORE_CASE)
            .containsMatchIn(line) -> "ساخت هویت WARP روی کلادفلر"
        Regex("""loaded existing (warp|masque) identity|identity ready""", RegexOption.IGNORE_CASE)
            .containsMatchIn(line) -> "هویت WARP بارگذاری شد"
        Regex("""verifying cached""", RegexOption.IGNORE_CASE).containsMatchIn(line) ->
            "بررسی گیتوی قبلی"
        Regex("""hunting for a working wireguard endpoint|candidate ok|rescanning|scanning fresh""",
            RegexOption.IGNORE_CASE).containsMatchIn(line) -> "اسکن اندپوینتهای WireGuard"
        Regex("""selected wireguard endpoint|using forced peer""", RegexOption.IGNORE_CASE)
            .containsMatchIn(line) -> "اندپوینت انتخاب شد"
        Regex("""confirming wireguard handshake|handshake successful""", RegexOption.IGNORE_CASE)
            .containsMatchIn(line) -> "دستدادن WireGuard"
        Regex("""no valid data from peer|wireguard tunnel stale|tunnel considered dead""",
            RegexOption.IGNORE_CASE).containsMatchIn(line) ->
            "پاسخی از سرور نرسید — اتصال مجدد"
        else -> null
    }

    private fun destroyProcess(child: Process? = process) {
        if (child == null) return
        if (process === child) process = null
        EngineProc.shutdown(child, { AppState.log("engine $id $it") }, "warp")
        lastLine = ""
        serving = false
    }

    private fun emit(code: Int, message: String, data: String) {
        listener?.invoke(code, message, data)
    }

    private fun writeTunnelConfig(): File {
        val file = File(workDir, TUNNEL_FILE)
        file.writeText(
            """
            tunnel:
              mtu: 1500
              ipv4: 172.19.0.2
              ipv6: 'fd00::2'
              icmp: 'reply'
            socks5:
              address: 127.0.0.1
              port: ${Prefs.socksPort}
              udp: 'udp'
            misc:
              task-stack-size: 32768
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    private val appContext: Context
        get() = context ?: error("WarpCore.init() was not called")

    private val workDir: File
        get() = File(appContext.filesDir, "warp")

    private val binary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libaether.so")

    private companion object {
        const val IDENTITY_FILE = "aether.toml"
        const val TUNNEL_FILE = "tunnel.yml"
        const val SERVING_MARKER = "socks5 server listening"

        /** Fields the engine cannot start without — the reference client's own check. */
        val IDENTITY_FIELDS = listOf(
            """(?m)^device_id = "\S+""",
            """(?m)^wg_private_key = "\S+""",
        )

        /** One register rung: long enough for ECH over a slow resolver. */
        const val REGISTER_ATTEMPT_MS = 35_000L
        const val REGISTER_TOTAL_MS = 180_000L

        /** Identity ladder plus a full balanced scan, inside the service's 900s. */
        const val CONNECT_BUDGET_MS = 300_000L
    }
}
