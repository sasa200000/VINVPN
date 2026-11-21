package okhttp3.internal.platform;

import android.content.Context;

/**
 * The single okhttp 5 symbol amneziawg-android's AbstractBackend constructor
 * calls, provided here because this app pins okhttp to 4.12: 5.x is built with
 * Kotlin 2.2 and its class metadata the project's Kotlin 1.9.24 cannot read
 * (see resolutionStrategy in app/build.gradle.kts).
 *
 * okhttp 5 added a process-wide registry that its platform detection reads an
 * application context from. Nothing in the paths this app runs (a plain-IP
 * WireGuard endpoint, no DNS over HTTPS) reads it back, so the call is absorbed
 * by this no-op — signature for signature: static INSTANCE, and
 * setApplicationContext(Context).
 */
public final class PlatformRegistry {
    public static final PlatformRegistry INSTANCE = new PlatformRegistry();

    private PlatformRegistry() {
    }

    public void setApplicationContext(Context context) {
        // No okhttp 5 registry to feed: okhttp 4 needs no context here.
    }
}
