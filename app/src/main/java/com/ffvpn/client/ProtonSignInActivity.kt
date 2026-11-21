package com.ffvpn.client

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.ProtonStore
import com.ffvpn.client.core.Prefs
import com.ffvpn.client.core.proton.ProtonAccess
import com.ffvpn.client.core.proton.ProtonApi
import com.ffvpn.client.service.ProxyService
import com.ffvpn.client.service.TunnelVpnService
import com.ffvpn.client.ui.VinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONObject
import java.net.Proxy

/**
 * Proton sign-in inside the app, as an account the person already made:
 * email + password typed into this screen's own form, and the form drives
 * Proton's real sign-in page underneath it — the URL carries the email, a
 * script fills the password when the page shows it and presses Sign in, and
 * whatever Proton then asks for (its device check, a code) is right there on
 * the visible page for the person to do. The password goes into the page's
 * field, not into an API call: Proton's login is a challenge protocol the app
 * does not reimplement, and the page handles it exactly as a browser would.
 *
 * The session that comes out is the page's own cookie (the password still
 * never reaches the app's own stores), and right after it appears the free
 * servers are taken through the route the page used. A session made in an
 * outside browser cannot be imported here — cookie stores do not share — so
 * this screen, not the browser, is where the account joins the app.
 *
 * On a filtered line the page needs a route: the running core's local SOCKS
 * ([ProtonAccess]) which the WebView is pointed at while the page is open, or
 * — when nothing is running and the line cannot reach Proton — the helper
 * engine this screen starts itself with one tap.
 */
class ProtonSignInActivity : ComponentActivity() {

    /** Whose tunnel the screen asked to start; run once consent comes back. */
    private var pendingHelper: String? = null

    /** Set by the screen: how a consent dialog it opened came back. */
    var onHelperConsent: ((Boolean) -> Unit)? = null

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ok = result.resultCode == RESULT_OK
        if (ok) runHelper() else pendingHelper = null
        onHelperConsent?.invoke(ok)
    }

    /**
     * Starts the helper engine the sign-in page needs: [coreId], the person's
     * own pick unless that pick is Proton itself (a tunnel cannot carry the
     * page into itself — then the censorship-first engine goes first).
     */
    fun startHelper(coreId: String) {
        pendingHelper = coreId
        val intent = VpnService.prepare(this)
        if (intent != null) {
            consent.launch(intent)
        } else {
            runHelper()
            onHelperConsent?.invoke(true)
        }
    }

    private fun runHelper() {
        val id = pendingHelper ?: return
        pendingHelper = null
        if (Prefs.mode == AppState.Mode.PROXY) {
            ProxyService.start(this, id)
        } else {
            TunnelVpnService.start(this, id)
        }
    }

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
    var needHelper by remember { mutableStateOf(false) }
    var waitingHelper by remember { mutableStateOf(false) }
    var formEmail by remember { mutableStateOf("") }
    var formPass by remember { mutableStateOf("") }

    /**
     * The address the page loads for, set the moment «افزودن حساب» is pressed:
     * the email rides in the URL (the page prefills itself), the password goes
     * in through the script only when there is one — a blank password means
     * «open the page», nothing is driven.
     */
    var submitted by remember { mutableStateOf<String?>(null) }

    val store = remember { ProtonStore(context) }
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The person's own engine, unless it is Proton itself — then the one built
    // for getting past exactly this kind of block.
    val helperId = remember {
        if (Prefs.coreId.isBlank() || Prefs.coreId == "proton") "psiphon" else Prefs.coreId
    }

    // The route the page will take and its proxy override, set before the page
    // is created: the override binds when the WebView first loads. No route and
    // no direct path means the helper panel instead of a dead page. Cleared on
    // the way out — the override is process-wide and must not outlive this.
    LaunchedEffect(refresh) {
        pageReady = false
        needHelper = false
        waitingHelper = false
        val inNow = withContext(Dispatchers.IO) {
            runCatching { ProtonApi.signedIn() }.getOrDefault(false)
        }
        signedIn = inNow
        if (inNow) return@LaunchedEffect
        val route = withContext(Dispatchers.IO) { ProtonAccess.current() }
        if (route != null) {
            val ok = ProtonAccess.setWebViewProxy(route)
            if (!ok) problem = Texts.protonProxyNote
            pageReady = true
        } else if (withContext(Dispatchers.IO) { ProtonAccess.reachable(null) }) {
            // The line reaches Proton itself: no tunnel to point the page at.
            ProtonAccess.clearWebViewProxy()
            pageReady = true
        } else {
            loading = false
            needHelper = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { ProtonAccess.clearWebViewProxy() }
    }

    // A consent dialog this screen opened: a refusal stops the wait at once.
    DisposableEffect(activity) {
        activity?.onHelperConsent = { granted ->
            if (!granted) {
                waitingHelper = false
                problem = Texts.protonConsentDenied
            }
        }
        onDispose { activity?.onHelperConsent = null }
    }

    // The helper that was started: once its tunnel carries traffic, the route
    // exists and the page goes. A session that dies on its own (the engine
    // gave up) fails the wait early with that engine's own words.
    LaunchedEffect(waitingHelper) {
        if (!waitingHelper) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        var sawSession = false
        while (System.currentTimeMillis() - startedAt < HELPER_WAIT_MS) {
            delay(1_000)
            val st = AppState.state
            if (st.connected && st.running) {
                waitingHelper = false
                needHelper = false
                val route = withContext(Dispatchers.IO) { ProtonAccess.current() }
                if (route != null) {
                    val ok = ProtonAccess.setWebViewProxy(route)
                    if (!ok) problem = Texts.protonProxyNote
                    pageReady = true
                } else {
                    refresh++
                }
                return@LaunchedEffect
            }
            if (st.running || st.busy) sawSession = true
            else if (sawSession && System.currentTimeMillis() - startedAt > 8_000) {
                waitingHelper = false
                problem = st.statusDetail.ifBlank { null } ?: Texts.protonHelperFailed
                return@LaunchedEffect
            }
        }
        waitingHelper = false
        problem = Texts.protonHelperFailed
    }

    // The sign-in the page completes. A cookie is NOT the end of it: Proton
    // sets it at the password, and may still want a code or a confirmation on
    // the page — so the page stays open until it says it is done (a persisted
    // session, or a URL away from /login). Then one automatic fetch, through
    // the route that got the page there; a refusal is shown, not retried into
    // a loop, and the button below is the way to try again.
    LaunchedEffect(refresh) {
        if (signedIn) return@LaunchedEffect
        var autoTried = false
        var cookieSeenAt = 0L
        var hinted = false
        while (true) {
            delay(1_000)
            val cookie = withContext(Dispatchers.IO) {
                runCatching { ProtonApi.signedIn() }.getOrDefault(false)
            }
            if (!cookie) continue
            if (cookieSeenAt == 0L) cookieSeenAt = System.currentTimeMillis()
            val meta = withContext(Dispatchers.Main) { pageMeta(webView) }
            val (version, uid, pageDone) = meta
            if (!pageDone) {
                if (!hinted && System.currentTimeMillis() - cookieSeenAt > 12_000) {
                    hinted = true
                    problem = Texts.protonPageSteps
                }
                continue
            }
            // The flip below tears the page down: take what it says about
            // itself first — `x-pm-appversion` must name a dashboard release
            // that exists, and the page's own session id orders the cookies.
            val ua = webView?.settings?.userAgentString.orEmpty()
            withContext(Dispatchers.IO) { store.saveMeta(version, ua, uid) }
            signedIn = true
            if (autoTried) break
            autoTried = true
            fetch(context, store) { fetching = it }?.let { outcome ->
                when (outcome) {
                    is ProtonApi.Outcome.Got,
                    is ProtonApi.Outcome.NothingNew -> onDone()
                    is ProtonApi.Outcome.NotSignedIn -> {
                        // The cookie is gone: back to the page, once.
                        signedIn = false
                        refresh++
                    }
                    is ProtonApi.Outcome.Expired -> {
                        // Present but refused (a session Proton still holds a
                        // step over): stay here and say it — the page is gone
                        // already, so the button retries with what we have.
                        problem = Texts.protonSessionInvalid
                    }
                    is ProtonApi.Outcome.Failed -> {
                        problem = "${Texts.protonFetchFailed}: ${outcome.detail}"
                    }
                }
            }
            break
        }
    }

    // The driver's device-check flag: said here, in words, when it appears.
    LaunchedEffect(pageReady, submitted) {
        while (!signedIn) {
            delay(2_000)
            val view = webView ?: continue
            if (submitted == null) continue
            withContext(Dispatchers.Main) {
                view.evaluateJavascript("(window.__vinvn_captcha||0)") { r ->
                    if (r == "\"1\"" && problem == null) {
                        problem = Texts.protonCaptchaNote
                    }
                }
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
        if (loading || fetching || waitingHelper) {
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
                            fetch(context, store) { fetching = it }?.let { outcome ->
                                when (outcome) {
                                    is ProtonApi.Outcome.Got,
                                    is ProtonApi.Outcome.NothingNew -> onDone()
                                    is ProtonApi.Outcome.NotSignedIn -> {
                                        signedIn = false
                                        refresh++
                                    }
                                    is ProtonApi.Outcome.Expired -> {
                                        problem = Texts.protonSessionInvalid
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
        } else {
            // The form the person fills: the account was made in a browser,
            // and this is where it joins the app.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = formEmail,
                    onValueChange = { formEmail = it },
                    label = { Text(Texts.protonEmail) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = formPass,
                    onValueChange = { formPass = it },
                    label = { Text(Texts.protonPassword) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(10.dp))
                Button(
                    onClick = {
                        if (formEmail.isBlank()) {
                            problem = Texts.protonEmailNeeded
                            return@Button
                        }
                        problem = null
                        // The URL carries the email; the script (armed with the
                        // password) fills the password step when it appears.
                        submitted = "$SIGN_IN_PAGE?username=${Uri.encode(formEmail.trim())}"
                        // No route and no direct path: the form's own button
                        // starts the helper too, so one tap does the whole job.
                        if (needHelper && !waitingHelper) {
                            waitingHelper = true
                            activity?.startHelper(helperId)
                        }
                    },
                    enabled = !waitingHelper && !fetching,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(Texts.protonAddAccount) }
                Text(
                    text = Texts.protonSignInHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (needHelper) {
                // The line cannot reach Proton and nothing is running: one
                // button starts the helper and waits for it here, in front of
                // the page that needs it, instead of sending the person
                // elsewhere to guess.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = Texts.protonHelperNeed,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(12.dp))
                    Button(
                        onClick = {
                            problem = null
                            waitingHelper = true
                            activity?.startHelper(helperId)
                        },
                        enabled = !waitingHelper,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (waitingHelper) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(Texts.protonWaitingHelper)
                        } else {
                            Text("${Texts.protonStartHelper} (${Texts.coreName(helperId)})")
                        }
                    }
                    TextButton(
                        onClick = {
                            problem = null
                            refresh++
                        },
                        enabled = !waitingHelper,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(Texts.protonRetry) }
                }
            } else if (pageReady) {
                AndroidView(
                    factory = { ctx ->
                        val start = submitted ?: SIGN_IN_PAGE
                        createPage(ctx, start, { loading = false }) {
                            buildDriveScript(formEmail, formPass, submitted)
                        }.also { webView = it }
                    },
                    update = { view ->
                        val target = submitted
                        if (target != null && view.tag != target) {
                            view.tag = target
                            view.loadUrl(target)
                        }
                        if (signedIn) view.stopLoading()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

/**
 * The page itself, and the driver: once a form was submitted, every page load
 * re-arms [drive] — the script that walks Proton's own sign-in (email in the
 * URL, password into the field when the step shows, Sign in pressed once) and
 * leaves whatever Proton then asks of the device to the person, on the page.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createPage(
    context: Context,
    startUrl: String,
    onLoaded: () -> Unit,
    drive: () -> String?,
): WebView {
    val view = WebView(context)
    view.settings.javaScriptEnabled = true
    view.settings.domStorageEnabled = true
    view.settings.mediaPlaybackRequiresUserGesture = false
    view.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            view.settings.javaScriptEnabled = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // Armed only when the form was filled: with no submission the page
            // stays exactly as a person left it.
            drive()?.let { js -> view.evaluateJavascript(js, null) }
            onLoaded()
        }
    }
    view.tag = startUrl
    view.loadUrl(startUrl)
    return view
}

/**
 * The script that finishes the sign-in the form started. Two steps on one
 * page: Continue (unless the URL already advanced it), then the password
 * field. React needs the native value setter and a bubbling input event, the
 * button is found by what it says (English or Persian), and a device check
 * Proton shows is never clicked through — that one is left to the person,
 * with a note on this screen.
 */
private fun buildDriveScript(email: String, password: String, submitted: String?): String? {
    if (submitted == null || password.isBlank() || email.isBlank()) return null
    val em = JSONObject.quote(email.trim())
    val pw = JSONObject.quote(password)
    // The run id changes when the form is submitted again with other words,
    // so a second attempt is not blocked by the first one's once-only guards.
    return """
        (function(){
          var em=$em, pw=$pw;
          function ss(k,v){try{if(v===undefined)return sessionStorage.getItem(k);if(v===null){sessionStorage.removeItem(k);return null;}sessionStorage.setItem(k,v);}catch(e){return null;}return null;}
          var run=em+'|'+pw;
          if(ss('vinvn_run')!==run){ss('vinvn_run',run);ss('vinvn_go',null);ss('vinvn_si',null);}
          function set(el,val){
            try{
              var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
              if(d&&d.set){d.set.call(el,val);}else{el.value=val;}
            }catch(e){el.value=val;}
            el.dispatchEvent(new Event('input',{bubbles:true}));
            el.dispatchEvent(new Event('change',{bubbles:true}));
          }
          function vis(b){return !!(b&&(b.offsetParent||b.getClientRects().length));}
          function subs(){return Array.prototype.slice.call(document.querySelectorAll('button[type=submit],input[type=submit]')); }
          function btn(re){var a=subs().filter(vis);for(var i=0;i<a.length;i++){var t=(a[i].innerText||a[i].value||'');if(re.test(t))return a[i];}return null;}
          function captcha(){return !!document.querySelector('iframe[src*=captcha],iframe[src*=hcaptcha],.h-captcha,[class*=captcha] iframe');}
          var n=0, si=false;
          var iv=setInterval(function(){
            if(++n>180){clearInterval(iv);return;}
            try{
              var u=document.querySelector('#username');
              var p=document.querySelector('#password')||document.querySelector('input[type=password]');
              if(u&&!u.value&&em){set(u,em);}
              if(p&&!p.value&&pw){set(p,pw);return;}
              if(p&&p.value&&pw&&!si){
                if(ss('vinvn_si')){clearInterval(iv);return;}
                if(captcha()){try{window.__vinvn_captcha=1;}catch(e){}n=0;return;}
                var b=btn(/sign\s*in|log\s*in|ورود/i)||btn(/./);
                if(b){si=true;ss('vinvn_si','1');try{window.__vinvn_captcha=0;}catch(e){}b.click();clearInterval(iv);}
                return;
              }
              if(!p&&u&&u.value){
                var c=btn(/continue|ادامه|بعدی/i);
                if(c&&!captcha()){c.click();return;}
              }
            }catch(e){}
          },500);
          return 'armed';
        })();
    """.trimIndent()
}

/**
 * Takes the account's free servers through the route the page is using (the
 * running core's listener, or the line) and saves them. Returns the outcome
 * after flipping [onBusy]; null if the store threw.
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
    val out = try {
        store.fetchAndSave(proxy)
    } catch (e: kotlinx.coroutines.CancellationException) {
        // A refresh that cancelled this attempt: not a failure, and not to be
        // logged as one — the effect that cancelled it has moved on.
        throw e
    } catch (t: Throwable) {
        null
    }
    onBusy(false)
    // The log is where the next failure is read from — every outcome, not
    // only the ones the screen can show.
    AppState.log(
        "proton sign-in fetch: " + when (out) {
            is ProtonApi.Outcome.Got -> "${out.confs.size} servers"
            is ProtonApi.Outcome.NothingNew -> "nothing new"
            is ProtonApi.Outcome.NotSignedIn -> "no session in the cookie jar"
            is ProtonApi.Outcome.Expired -> "session refused (code/scope)"
            is ProtonApi.Outcome.Failed -> "failed: ${out.detail}"
            null -> "store error"
        }
    )
    return out
}

/** What the page says about itself, in one read — see [pageMeta]. */
private data class PageMeta(val version: String, val uid: String, val done: Boolean)

/**
 * The dashboard's release (`SENTRY_RELEASE.id` — what `x-pm-appversion` must
 * name), the persisted session id (the `ps-<n>` entries the dashboard writes
 * only when a sign-in has fully completed — including any code Proton
 * wanted), and whether the URL has left the sign-in flow. All three are read
 * while the page is still alive: signing in flips the screen and disposes the
 * WebView.
 */
private suspend fun pageMeta(view: WebView?): PageMeta {
    if (view == null) return PageMeta("", "", true)
    val raw = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine<String> { cont ->
            try {
                view.evaluateJavascript(PAGE_META_JS) { r ->
                    if (cont.isActive) cont.resume(r ?: "")
                }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume("")
            }
        }
    } ?: return PageMeta("", "", false)
    val body = raw.trim().trim('"')
    val version = body.substringBefore('|')
    val uid = body.substringAfter('|', "")
    val url = view.url
    val done = uid.isNotBlank() || (url != null && !url.contains("/login"))
    return PageMeta(if (RELEASE_PATTERN.matches(version)) version else "", uid, done)
}

/** The one evaluate: version, persisted session id, joined by a pipe. */
private const val PAGE_META_JS = """(function(){try{
var v=(window.SENTRY_RELEASE&&SENTRY_RELEASE.id)||'';
var best='',at=-1;
for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(!/^ps-\d+$/.test(k))continue;
var o=JSON.parse(localStorage.getItem(k)||'{}');var t=o.persistedAt||0;
if(o.UID&&t>=at){best=o.UID;at=t;}}
return v+'|'+best;}catch(e){return '|';}})()"""

private val RELEASE_PATTERN = Regex("""\d+\.\d+\.\d+\.\d+""")

private const val SIGN_IN_PAGE = "https://account.protonvpn.com/login"

/** Long enough for a slow engine's own retry ladder to either land or give up. */
private const val HELPER_WAIT_MS = 150_000L
