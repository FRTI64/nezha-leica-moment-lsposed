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
    public static final String MODULE_VERSION = "2.7.1";

    /** SharedPreferences file used by the module app (private). */
    public static final String PREFS_NAME = "leica_lsposed_config";

    /** ContentProvider authority of {@link CookieConfigProvider}. */
    public static final String AUTHORITY = "com.nezha.leica.lsposed.config";

    public static final String METHOD_GET_CONFIG = "get_config";
    /** On-device cloud-request trace (URL path + HTTP code only; no tokens/query/body). */
    public static final String METHOD_SET_CLOUD_TRACE = "set_cloud_trace";
    public static final String METHOD_GET_CLOUD_TRACE = "get_cloud_trace";
    /** mediaeditor process reports which legend-fallback variant matched (CN|GLOBAL|none). */
    public static final String METHOD_SET_LEGEND_FALLBACK_TARGET = "set_legend_fallback_target";
    /** camera process reports which MakerNote tag-map variant was extended (CN|GLOBAL|none). */
    public static final String METHOD_SET_CAMERA_MAKERNOTE_TARGET = "set_camera_makernote_target";
    /** v2.6.1: capture create-request JSON + server response body (local only, last 8). */
    public static final String METHOD_APPEND_CLOUD_CAPTURE = "append_cloud_capture";
    /** mediaeditor process reports the effective cloud base URL after rewrite. */
    public static final String METHOD_SET_CLOUD_ENDPOINT = "set_cloud_endpoint";
    /** mediaeditor process reports CN region simulation state (simulated|off|none). */
    public static final String METHOD_SET_CN_REGION = "set_cn_region";

    public static final String KEY_COOKIE = "cookie";
    public static final String KEY_ENABLE_GATE_BYPASS = "enable_gate_bypass";
    public static final String KEY_ENABLE_COOKIE = "enable_cookie";
    /** Diagnostics only (key names / source domains; never token values). */
    public static final String KEY_COOKIE_SOURCES = "cookie_sources";
    public static final String KEY_COOKIE_DUP_KEYS = "cookie_dup_keys";
    public static final String KEY_FORCE_CTA = "force_cta";
    /** v2.5.1 option B: inject legend version fallback (V2/M3) so no-maker-note
     *  photos can take the cleaned-upload cloud fallback path. Default true. */
    public static final String KEY_FORCE_LEGEND_FALLBACK = "force_legend_fallback";
    /** v2.5.3: fill missing CreateLegendV2TaskParams (aperture/focus/oaid) at job time. Default true. */
    public static final String KEY_FORCE_LEGEND_CLOUD_PARAMS = "force_legend_cloud_params";
    /** v2.6.0: extend camera MakerNote tag map with CN-only Moment* tags. Default true. */
    public static final String KEY_SYNC_CN_CAMERA_MAKERNOTE = "sync_cn_camera_makernote";
    /** Newline-joined cloud request trace lines (no token values). */
    public static final String KEY_CLOUD_TRACE = "cloud_trace";
    /** CN | GLOBAL | none (which legend-fallback hook variant matched on device). */
    public static final String KEY_LEGEND_FALLBACK_TARGET = "legend_fallback_target";
    /** CN | GLOBAL | none (which camera MakerNote map was extended). */
    public static final String KEY_CAMERA_MAKERNOTE_TARGET = "camera_makernote";
    /** Newline-joined last 8 captured cloud payloads (request/response JSON, local only). */
    public static final String KEY_CLOUD_CAPTURE = "cloud_capture";
    /** v2.7.0: rewrite AI cloud base URL to CN endpoint. Default true (A/B test). */
    public static final String KEY_FORCE_CN_CLOUD_ENDPOINT = "force_cn_cloud_endpoint";
    /** Effective cloud endpoint reported by mediaeditor (rewritten/off/none). */
    public static final String KEY_CLOUD_ENDPOINT = "cloud_endpoint";
    /** v2.7.1: simulate CN region flag so cloud config returns the real CN base URL. Default true. */
    public static final String KEY_SIMULATE_CN_REGION = "simulate_cn_region";
    /** simulated | off | none (reported by mediaeditor process). */
    public static final String KEY_CN_REGION = "cn_region";

    /** Defaults (also used when the provider is unreachable). */
    public static final boolean DEFAULT_ENABLE_GATE_BYPASS = true;
    public static final boolean DEFAULT_ENABLE_COOKIE = true;
    public static final boolean DEFAULT_FORCE_CTA = false;
    public static final boolean DEFAULT_FORCE_LEGEND_FALLBACK = true;
    public static final boolean DEFAULT_FORCE_LEGEND_CLOUD_PARAMS = true;
    public static final boolean DEFAULT_SYNC_CN_CAMERA_MAKERNOTE = true;
    public static final boolean DEFAULT_FORCE_CN_CLOUD_ENDPOINT = false;
    public static final boolean DEFAULT_SIMULATE_CN_REGION = true;

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
        b.putBoolean("force_legend_fallback",
                p.getBoolean(KEY_FORCE_LEGEND_FALLBACK, DEFAULT_FORCE_LEGEND_FALLBACK));
        b.putBoolean("force_legend_cloud_params",
                p.getBoolean(KEY_FORCE_LEGEND_CLOUD_PARAMS, DEFAULT_FORCE_LEGEND_CLOUD_PARAMS));
        b.putBoolean("sync_cn_camera_makernote",
                p.getBoolean(KEY_SYNC_CN_CAMERA_MAKERNOTE, DEFAULT_SYNC_CN_CAMERA_MAKERNOTE));
        b.putString("cloud_trace", p.getString(KEY_CLOUD_TRACE, ""));
        b.putString("legend_fallback_target", p.getString(KEY_LEGEND_FALLBACK_TARGET, ""));
        b.putString("camera_makernote", p.getString(KEY_CAMERA_MAKERNOTE_TARGET, ""));
        b.putBoolean("force_cn_cloud_endpoint",
                p.getBoolean(KEY_FORCE_CN_CLOUD_ENDPOINT, DEFAULT_FORCE_CN_CLOUD_ENDPOINT));
        b.putBoolean("simulate_cn_region",
                p.getBoolean(KEY_SIMULATE_CN_REGION, DEFAULT_SIMULATE_CN_REGION));
        b.putString("cloud_endpoint", p.getString(KEY_CLOUD_ENDPOINT, ""));
        b.putString("cn_region", p.getString(KEY_CN_REGION, ""));
        b.putString("cloud_capture", p.getString(KEY_CLOUD_CAPTURE, ""));
        b.putString("module_version", MODULE_VERSION);
        return b;
    }
}
