package com.ffvpn.client.core

import android.content.Context
import com.ffvpn.client.core.proton.ProtonApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy

/**
 * What the Proton account gave this app: the free servers as WireGuard
 * configs, the key registered for them, and which config is next to try.
 *
 * The configs are the dashboard's own text — the same file Proton's site would
 * have downloaded — kept as-is so the AmneziaWG backend parses exactly what
 * Proton wrote. One key serves every server (measured: the key registered for
 * one server carried data on two others), so a renewal rewrites the whole list
 * and an addition merges by endpoint.
 */
class ProtonStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("proton", Context.MODE_PRIVATE)

    fun confs(): List<ProtonApi.Conf> {
        val raw = runCatching { JSONArray(prefs.getString(KEY_CONFS, null).orEmpty()) }.getOrNull()
            ?: return emptyList()
        return (0 until raw.length()).mapNotNull { i ->
            val o = raw.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text")
            if (text.isBlank()) null
            else ProtonApi.Conf(o.optString("name"), o.optString("country").ifBlank { null }, text)
        }
    }

    fun key(): ProtonApi.AccountKey? {
        val o = runCatching { JSONObject(prefs.getString(KEY_KEY, null).orEmpty()) }.getOrNull() ?: return null
        val private = o.optString("privateKey")
        if (private.isBlank()) return null
        return ProtonApi.AccountKey(private, o.optLong("expiresAt", 0L))
    }

    /** The entry endpoints the configs already point at, so none is taken twice. */
    fun endpoints(): Set<String> =
        confs().mapNotNull { ENDPOINT.find(it.text)?.groupValues?.get(1) }.toSet()

    fun save(confs: List<ProtonApi.Conf>, key: ProtonApi.AccountKey?, v6: Map<String, String>) {
        val arr = JSONArray()
        confs.forEach { c ->
            arr.put(JSONObject().put("name", c.name).put("country", c.country ?: "").put("text", c.text))
        }
        val v6o = JSONObject()
        v6.forEach { (k, v) -> v6o.put(k, v) }
        prefs.edit()
            .putString(KEY_CONFS, arr.toString())
            .putString(KEY_V6, v6o.toString())
            .putInt(KEY_INDEX, 0)
            .apply()
        if (key != null) {
            prefs.edit().putString(KEY_KEY, JSONObject().put("privateKey", key.privateKey)
                .put("expiresAt", key.expiresAt).toString()).apply()
        }
    }

    /**
     * The config the next attempt runs: round-robin over the account's servers,
     * so a retry ladder that fails one server asks for the next instead of the
     * same one again.
     */
    fun next(): ProtonApi.Conf? {
        val all = confs()
        if (all.isEmpty()) return null
        val i = (prefs.getInt(KEY_INDEX, 0) + 1)
        prefs.edit().putInt(KEY_INDEX, i % all.size).apply()
        return all[i % all.size]
    }

    /**
     * Takes the account's free servers through [proxy] (the route the sign-in
     * page reached Proton with, or the line itself) and keeps the answer.
     * Runs off the main thread; blocking.
     */
    suspend fun fetchAndSave(proxy: Proxy): ProtonApi.Outcome = withContext(Dispatchers.IO) {
        val out = ProtonApi.takeFreeServers(
            proxy, "", ProtonApi.USER_AGENT, endpoints(), key()
        )
        if (out is ProtonApi.Outcome.Got) {
            // A new key means every config was made with it: the old ones go.
            val base = if (out.key != null) emptyList<ProtonApi.Conf>() else confs()
            val merged = (base + out.confs).distinctBy { ENDPOINT.find(it.text)?.groupValues?.get(1) ?: it.name }
            val oldV6 = runCatching {
                val o = JSONObject(prefs.getString(KEY_V6, null).orEmpty())
                o.keys().asSequence().associateWith { o.optString(it) }
            }.getOrDefault(emptyMap())
            save(merged, out.key, oldV6 + out.v6)
        }
        out
    }

    companion object {
        private const val KEY_CONFS = "confs"
        private const val KEY_KEY = "key"
        private const val KEY_V6 = "v6"
        private const val KEY_INDEX = "idx"
        private val ENDPOINT = Regex("""Endpoint = (\S+)""")
    }
}
