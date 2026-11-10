package com.nezha.leica.lsposed;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/**
 * Shared constants and helpers for the LeicaMoment LSPosed module.
 *
 * <p>This class is loaded both in the module app process and in the hooked
 * mediaeditor/gallery processes, therefore it must NOT reference any
 * de.robv.android.xposed.* class.</p>
 */
public final class ModuleConfig {

    /** Human readable module version, also reported by the ContentProvider. */
    public static final String MODULE_VERSION = "2.4.1";

    /** SharedPreferences file used by the module app (private). */
    public static final String PREFS_NAME = "leica_lsposed_config";

    /** ContentProvider authority of {@link CookieConfigProvider}. */
    public static final String AUTHORITY = "com.nezha.leica.lsposed.config";

    public static final String METHOD_GET_CONFIG = "get_config";

    public static final String KEY_COOKIE = "cookie";
    public static final String KEY_ENABLE_GATE_BYPASS = "enable_gate_bypass";
    public static final String KEY_ENABLE_COOKIE = "enable_cookie";
    /** Diagnostics only (key names / source domains; never token values). */
    public static final String KEY_COOKIE_SOURCES = "cookie_sources";
    public static final String KEY_COOKIE_DUP_KEYS = "cookie_dup_keys";
    public static final String KEY_FORCE_CTA = "force_cta";

    /** Defaults (also used when the provider is unreachable). */
    public static final boolean DEFAULT_ENABLE_GATE_BYPASS = true;
    public static final boolean DEFAULT_ENABLE_COOKIE = true;
    public static final boolean DEFAULT_FORCE_CTA = false;

    private ModuleConfig() {
    }

    /** URI of the config provider. */
    public static Uri providerUri() {
        return Uri.parse("content://" + AUTHORITY);
    }

    /** Module app preferences. */
    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Builds the Bundle returned by the provider to the hooked process.
     * Keys: cookie (user-supplied only), enable_gate_bypass, enable_cookie,
     * force_cta, module_version. The app's system-account cookie is never used.
     */
    public static Bundle buildConfigBundle(Context context) {
        SharedPreferences p = prefs(context);
        Bundle b = new Bundle();
        b.putString("cookie", p.getString(KEY_COOKIE, ""));
        b.putBoolean("enable_gate_bypass", p.getBoolean(KEY_ENABLE_GATE_BYPASS, DEFAULT_ENABLE_GATE_BYPASS));
        b.putBoolean("enable_cookie", p.getBoolean(KEY_ENABLE_COOKIE, DEFAULT_ENABLE_COOKIE));
        b.putBoolean("force_cta", p.getBoolean(KEY_FORCE_CTA, DEFAULT_FORCE_CTA));
        b.putString("module_version", MODULE_VERSION);
        return b;
    }
}
