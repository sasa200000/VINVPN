package com.ffvpn.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ffvpn.client.core.ProtonStore
import com.ffvpn.client.core.proton.ProtonAccess
import com.ffvpn.client.core.proton.ProtonApi
import com.ffvpn.client.ui.VinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Proxy

/**
 * Proton's own sign-in page, inside the app — and with it, account creation:
 * the page's «Create account» flow runs here whole (the email code, the CAPTCHA
 * and all), and Proton's password stays between the person and Proton; the app
 * only ever sees the session cookie the page leaves behind.
 *
 * When Proton's site is filtered, the page is pointed at the running core's
 * local SOCKS listener first ([ProtonAccess]) — so the app says, on the way in,
 * to turn another engine on before signing in. Right after the sign-in is
 * detected, the free servers are taken through that same route and saved, which
 * is the moment the line still carries them; Proton's own tunnel cannot run
 * inside another engine's, so fetching later, mid-connect, would be direct and
 * filtered.
 */
class ProtonSignInActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        AppLocale.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VinTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ProtonSignInScreen(onDone = {
                        setResult(RESULT_OK)
                        finish()
                    })
                }
            }
        }
    }
}

@Composable
private fun ProtonSignInScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ProtonSignInActivity
    var pageReady by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var fetching by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    val store = remember { ProtonStore(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // The route the page will take and its proxy override, set before the page
    // is created: the override binds when the WebView first loads. Cleared on
    // the way out — it is process-wide and must not outlive this screen.
    LaunchedEffect(refresh) {
        pageReady = false
        val route = withContext(Dispatchers.IO) {
            signedIn = runCatching { ProtonApi.signedIn() }.getOrDefault(false)
            ProtonAccess.current()
        }
        if (route != null) {
            val ok = ProtonAccess.setWebViewProxy(route)
            if (!ok) {
                // A WebView too old for proxy overrides: the page would go
                // direct into the filter. Said plainly instead of spinning.
                problem = Texts.protonProxyNote
            }
        }
        pageReady = true
    }
    DisposableEffect(Unit) {
        onDispose { ProtonAccess.clearWebViewProxy() }
    }

    // The sign-in the page completes: the session is a cookie, so watching for
    // it is the whole handshake. Once it appears, the servers are taken right
    // away through the route that got the page there.
    LaunchedEffect(refresh) {
        if (signedIn) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val now = withContext(Dispatchers.IO) {
                runCatching { ProtonApi.signedIn() }.getOrDefault(false)
            }
            if (now) {
                signedIn = true
                fetch(context, store) { state ->
                    fetching = state
                }?.let { outcome ->
                    when (outcome) {
                        is ProtonApi.Outcome.Got, is ProtonApi.Outcome.NothingNew -> onDone()
                        is ProtonApi.Outcome.NotSignedIn, is ProtonApi.Outcome.Expired -> {
                            signedIn = false
                            refresh++
                        }
                        is ProtonApi.Outcome.Failed -> {
                            problem = "${Texts.protonFetchFailed}: ${outcome.detail}"
                        }
                    }
                }
                break
            }
        }
    }

    BackHandler { activity?.finish() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = Texts.protonSection,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { activity?.finish() }) {
                Text(Texts.actionCancel)
            }
        }
        Text(
            text = Texts.protonSignInHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
        )
        if (loading || fetching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (fetching) {
            Text(
                text = Texts.protonGettingServers,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
            )
        }
        problem?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
            )
        }
        if (signedIn) {
            // The session is in; only the servers are left, and the buttons
            // for taking them (or for signing out and starting over).
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = Texts.protonSignedIn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = {
                        problem = null
                        scope.launch {
                            fetch(context, store) { state -> fetching = state }?.let { outcome ->
                                when (outcome) {
                                    is ProtonApi.Outcome.Got,
                                    is ProtonApi.Outcome.NothingNew -> onDone()
                                    is ProtonApi.Outcome.NotSignedIn,
                                    is ProtonApi.Outcome.Expired -> {
                                        signedIn = false
                                        refresh++
                                    }
                                    is ProtonApi.Outcome.Failed -> {
                                        problem = "${Texts.protonFetchFailed}: ${outcome.detail}"
                                    }
                                }
                            }
                        }
                    },
                    enabled = !fetching,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (fetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.size(10.dp))
                    }
                    Text(if (fetching) Texts.protonGettingServers else Texts.protonContinue)
                }
                TextButton(
                    onClick = {
                        runCatching { ProtonApi.forgetSession() }
                        problem = null
                        refresh++
                    },
                    enabled = !fetching,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(Texts.protonSignOut) }
            }
        } else if (pageReady) {
            AndroidView(
                factory = { view -> createPage(view) { loading = false } },
                update = { if (signedIn) it.stopLoading() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * The page itself. No hooks, no bridges: Proton's own sign-in runs as in a
 * browser, and the session shows up as a cookie — which is the only thing the
 * screen reads.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createPage(view: WebView, onLoaded: () -> Unit): WebView {
    view.settings.javaScriptEnabled = true
    view.settings.domStorageEnabled = true
    view.settings.mediaPlaybackRequiresUserGesture = false
    view.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            view.settings.javaScriptEnabled = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            onLoaded()
        }
    }
    view.loadUrl(SIGN_IN_PAGE)
    return view
}

/**
 * Takes the account's free servers through the route the page is using (the
 * running core's listener, or the line) and saves them. Returns the outcome on
 * the caller's thread after flipping [onBusy]; null if the store threw.
 */
private suspend fun fetch(
    context: Context,
    store: ProtonStore,
    onBusy: (Boolean) -> Unit,
): ProtonApi.Outcome? {
    onBusy(true)
    val proxy = withContext(Dispatchers.IO) {
        ProtonAccess.current()?.javaProxy() ?: Proxy.NO_PROXY
    }
    val out = runCatching { store.fetchAndSave(proxy) }.getOrNull()
    onBusy(false)
    return out
}

private const val SIGN_IN_PAGE = "https://account.protonvpn.com/"
