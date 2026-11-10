package com.nezha.leica.lsposed;

import android.content.Context;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

import android.app.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Legacy Xposed entry ({@code assets/xposed_init} -&gt; com.nezha.leica.lsposed.HookEntry).
 *
 * <p>Hooks installed inside {@code com.miui.mediaeditor}:</p>
 * <ol>
 *   <li>SystemProperties.get/getBoolean/getInt spoofing (gating bypass):
 *       ro.miui.build.region=cn, ro.theme_customize=LCC, ro.theme_leica=true,
 *       ro.boot.product.theme_customize=P1_Leica, ro.special_edition=true.
 *       Evidence: work/leica/docs/ANALYSIS.md section 3.1/3.2.</li>
 *   <li>Xiaomi STS cookie provider injection: obfuscated zero-arg static
 *       {@code String b()} in CN 2.10.43.0.6 ({@code go.e}) and GLOBAL
 *       2.10.40.4.8-global ({@code bs.e}). The original result (when non-empty)
 *       is reported back to the module app as "captured cookie".</li>
 *   <li>AccountUtil login probe ("uploadFile: failed, not login" guard):
 *       obfuscated {@code ga0.e.a(Context)} (CN) / {@code be0.e.a(Context)}
 *       (GLOBAL); when no system Xiaomi account exists but the user supplied
 *       a cookie, a non-empty account id derived from the cookie
 *       (userId/cUserId, fallback "demo") is returned so the upload proceeds.
 *       Evidence: work/leica/docs/lsposed_hook_targets.md section 3.</li>
 *   <li>CTA force - intentionally NOT installed: the CTA is a normal
 *       user-consented privacy dialog and the legend entry visibility is
 *       props-only (no CTA input). Only a log line is emitted if requested.</li>
 * </ol>
 *
 * <p>{@code com.miui.gallery} (HyperOS 4) is a Flutter + Rust app with no Java
 * code of its own (Dart AOT {@code libapp.so} + Rust {@code libapp_gallery.so}
 * via flutter_rust_bridge). Dart/Rust cannot be hooked from LSPosed, so for the
 * gallery process the module installs only the SystemProperties spoof hooks as
 * a safety net for the Java/JNI system-property bridge used by its Rust
 * capability probes. Cookie/account hooks stay mediaeditor-only.</p>
 *
 * <p>Every hook installation and every hook body is wrapped in try/catch so a
 * version mismatch can never crash the target app.</p>
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "LeicaMomentLSP: ";

    private static final String PKG_EDITOR = "com.miui.mediaeditor";
    private static final String PKG_GALLERY = "com.miui.gallery";

    /** How long a fetched config Bundle is considered fresh (toggles apply without restart). */
    private static final long CONFIG_TTL_MS = 8000L;

    /** SystemProperties keys spoofed inside the mediaeditor process. */
    private static final Map<String, String> SPOOF_PROPS = new HashMap<String, String>();

    static {
        SPOOF_PROPS.put("ro.miui.build.region", "cn");
        SPOOF_PROPS.put("ro.theme_customize", "LCC");
        SPOOF_PROPS.put("ro.theme_leica", "true");
        SPOOF_PROPS.put("ro.boot.product.theme_customize", "P1_Leica");
        SPOOF_PROPS.put("ro.special_edition", "true");
    }

    /**
     * Obfuscated cookie providers: {class, method}. Both return the Xiaomi STS
     * Cookie header string assembled from the system account.
     */
    private static final String[][] COOKIE_TARGETS = new String[][]{
            {"go.e", "b"}, // com.miui.mediaeditor 2.10.43.0.6 (CN)
            {"bs.e", "b"}  // com.miui.mediaeditor 2.10.40.4.8-global (GLOBAL)
    };

    /**
     * AccountUtil login probe: {class, method}. Static method taking a Context
     * and returning the first Xiaomi account name, or null when logged out.
     * The image-upload step aborts with "uploadFile: failed, not login" when it
     * gets null/blank, so on logged-out devices we derive an id from the cookie.
     */
    private static final String[][] ACCOUNT_PROBE_TARGETS = new String[][]{
            {"ga0.e", "a"}, // com.miui.mediaeditor 2.10.43.0.6 (CN)
            {"be0.e", "a"}  // com.miui.mediaeditor 2.10.40.4.8-global (GLOBAL)
    };

    private static volatile Bundle sConfig;
    private static volatile long sConfigTs;
    private static volatile Context sAppContext;
    private static volatile String sLastReportedCookie;
    private static volatile boolean sCookieInjectionLogged;
    private static volatile boolean sCookieCacheLogged;
    private static volatile boolean sCookieCacheWarned;
    private static volatile boolean sAccountProbeLogged;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null) {
            return;
        }
        String pkg = lpparam.packageName;
        if (!PKG_EDITOR.equals(pkg) && !PKG_GALLERY.equals(pkg)) {
            return;
        }
        try {
            log("handleLoadPackage: pkg=" + pkg + " process=" + lpparam.processName);
            if (!PKG_EDITOR.equals(pkg)) {
                // com.miui.gallery is Flutter + Rust (HyperOS 4): Dart AOT libapp.so +
                // Rust libapp_gallery.so (flutter_rust_bridge). Dart/Rust cannot be hooked
                // from LSPosed, but gallery's Java/JNI SystemPropertyMethodChannel bridge
                // still reads android.os.SystemProperties, and its Rust capability probe
                // calls the mediaeditor provider. Install the props spoof as a safety net.
                hookSystemProperties(lpparam);
                log("gallery scope: SystemProperties spoof hooks installed (Rust bridge safety net)");
                return;
            }
            hookSystemProperties(lpparam);
            hookCookieProviders(lpparam);
            hookAccountProbe(lpparam);
            log("all hook installers finished for " + pkg);
        } catch (Throwable t) {
            log("FATAL hook installation error: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    private static void log(String msg) {
        try {
            XposedBridge.log(TAG + msg);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // (1) SystemProperties gating bypass
    // ---------------------------------------------------------------------

    private static void hookSystemProperties(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    "get", String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!propsEnabled()) {
                                    return;
                                }
                                Object key = param.args.length > 0 ? param.args[0] : null;
                                if (!(key instanceof String)) {
                                    return;
                                }
                                String spoof = SPOOF_PROPS.get(key);
                                if (spoof != null) {
                                    param.setResult(spoof);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("hooked SystemProperties.get(String)");
        } catch (Throwable t) {
            log("SystemProperties.get(String) hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    "get", String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!propsEnabled()) {
                                    return;
                                }
                                Object key = param.args.length > 0 ? param.args[0] : null;
                                if (!(key instanceof String)) {
                                    return;
                                }
                                String spoof = SPOOF_PROPS.get(key);
                                if (spoof != null) {
                                    param.setResult(spoof);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("hooked SystemProperties.get(String, String)");
        } catch (Throwable t) {
            log("SystemProperties.get(String, String) hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    "getBoolean", String.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!propsEnabled()) {
                                    return;
                                }
                                Object key = param.args.length > 0 ? param.args[0] : null;
                                if (!(key instanceof String)) {
                                    return;
                                }
                                String spoof = SPOOF_PROPS.get(key);
                                if (spoof != null) {
                                    param.setResult(Boolean.valueOf(
                                            "true".equalsIgnoreCase(spoof)
                                                    || "1".equals(spoof)));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("hooked SystemProperties.getBoolean(String, boolean)");
        } catch (Throwable t) {
            log("SystemProperties.getBoolean hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.os.SystemProperties", cl,
                    "getInt", String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!propsEnabled()) {
                                    return;
                                }
                                Object key = param.args.length > 0 ? param.args[0] : null;
                                if (!(key instanceof String)) {
                                    return;
                                }
                                String spoof = SPOOF_PROPS.get(key);
                                if (spoof != null) {
                                    param.setResult(Integer.valueOf(Integer.parseInt(spoof)));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("hooked SystemProperties.getInt(String, int)");
        } catch (Throwable t) {
            log("SystemProperties.getInt hook failed: " + t);
        }
    }

    private static boolean propsEnabled() {
        try {
            return config().getBoolean("enable_props", ModuleConfig.DEFAULT_ENABLE_PROPS);
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------------------------------------------------------------
    // (2) Cookie provider injection
    // ---------------------------------------------------------------------

    private static void hookCookieProviders(XC_LoadPackage.LoadPackageParam lpparam) {
        int installed = 0;
        for (int i = 0; i < COOKIE_TARGETS.length; i++) {
            final String className = COOKIE_TARGETS[i][0];
            final String methodName = COOKIE_TARGETS[i][1];
            try {
                final Class<?> target = XposedHelpers.findClass(className, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(target, methodName, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (result instanceof String) {
                                String original = (String) result;
                                if (original != null && !original.trim().isEmpty()) {
                                    reportCapturedCookie(original);
                                }
                            }
                            String cookie = userCookie();
                            if (!cookie.isEmpty()) {
                                if (!sCookieInjectionLogged) {
                                    sCookieInjectionLogged = true;
                                    log("injecting user cookie (" + cookie.length()
                                            + " chars) via " + className + "." + methodName + "()");
                                }
                                param.setResult(cookie);
                                // CRITICAL: the mediaeditor network interceptor
                                // (CN nm/f.a()) caches the cookie in a static field
                                // (go.e.b / bs.e.b) and later requests take the
                                // cached-field fast path. Patching only the return
                                // value would fix the FIRST request only, so also
                                // force the static cache field.
                                forceCookieCache(target, cookie);
                            }
                        } catch (Throwable t) {
                            log("cookie hook body error: " + t);
                        }
                    }
                });
                installed++;
                log("cookie hook installed: " + className + "." + methodName + "()Ljava/lang/String;");
            } catch (Throwable t) {
                log("cookie target not present (normal on other app versions): "
                        + className + "." + methodName + " -> " + t);
            }
        }
        if (installed == 0) {
            log("WARN: no cookie provider target installed on this mediaeditor version; "
                    + "cookie injection will not work");
        }

        // (3) CTA force: no clean target for the analysed versions.
        boolean cta = false;
        try {
            cta = config().getBoolean("force_cta", ModuleConfig.DEFAULT_FORCE_CTA);
        } catch (Throwable ignored) {
        }
        if (cta) {
            log("force_cta requested but intentionally NOT installed: CTA is a normal privacy "
                    + "dialog and the legend entry visibility is props-only; accept the CTA "
                    + "dialog once in the app if prompted");
        } else {
            log("force_cta disabled (default) - CTA is a normal privacy dialog, "
                    + "accept it manually in the app");
        }
    }

    // ---------------------------------------------------------------------
    // (4) AccountUtil login probe (logged-out device + user-supplied cookie)
    // ---------------------------------------------------------------------

    private static void hookAccountProbe(XC_LoadPackage.LoadPackageParam lpparam) {
        int installed = 0;
        for (int i = 0; i < ACCOUNT_PROBE_TARGETS.length; i++) {
            final String className = ACCOUNT_PROBE_TARGETS[i][0];
            final String methodName = ACCOUNT_PROBE_TARGETS[i][1];
            try {
                final Class<?> target = XposedHelpers.findClass(className, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(target, methodName, Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String cookie = userCookie();
                            if (cookie.isEmpty()) {
                                return;
                            }
                            Object result = param.getResult();
                            String account = "";
                            if (result instanceof String) {
                                account = ((String) result).trim();
                            }
                            if (!account.isEmpty()) {
                                // Real system account exists - keep the original value.
                                return;
                            }
                            String derived = deriveAccountName(cookie);
                            param.setResult(derived);
                            if (!sAccountProbeLogged) {
                                sAccountProbeLogged = true;
                                log("account probe hook: returned derived account id ("
                                        + derived + ") because no system Xiaomi account is present");
                            }
                        } catch (Throwable t) {
                            log("account probe hook body error: " + t);
                        }
                    }
                });
                installed++;
                log("account probe hook installed: " + className + "." + methodName
                        + "(android.content.Context)");
            } catch (Throwable t) {
                log("account probe target not present (normal on other app versions): "
                        + className + "." + methodName + " -> " + t);
            }
        }
        if (installed == 0) {
            log("WARN: no account probe hook installed; on devices without a system Xiaomi "
                    + "account the upload may fail with 'uploadFile: failed, not login'");
        }
    }

    /**
     * Derives a non-empty account id from the configured cookie so the upload
     * login probe passes on logged-out devices: prefers userId / cUserId
     * (case-insensitive, URL-decoded), falls back to the literal "demo" which
     * the app itself uses in one branch.
     */
    private static String deriveAccountName(String cookie) {
        String value = findCookieValue(cookie, "userId");
        if (value.isEmpty()) {
            value = findCookieValue(cookie, "cUserId");
        }
        return value.isEmpty() ? "demo" : value;
    }

    /** Finds a cookie pair value by case-insensitive name. */
    private static String findCookieValue(String cookie, String name) {
        if (cookie == null) {
            return "";
        }
        String[] parts = cookie.split(";");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            int eq = part.indexOf('=');
            if (eq <= 0 || eq >= part.length() - 1) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            if (!key.equalsIgnoreCase(name)) {
                continue;
            }
            String value = part.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            try {
                String decoded = android.net.Uri.decode(value);
                if (decoded != null && !decoded.isEmpty()) {
                    return decoded;
                }
            } catch (Throwable ignored) {
            }
            return value;
        }
        return "";
    }

    /**
     * Forces the obfuscated cookie-provider static cache field "b" to the user
     * cookie.
     *
     * <p>The mediaeditor HTTP interceptor (CN {@code nm/f.a()}) first reads the
     * static field {@code go.e.b} (GLOBAL: {@code bs.e.b}); if it is non-empty
     * and the current account name equals the cached account field {@code c},
     * the cached value is sent as the Cookie header without calling
     * {@code b()} at all. Therefore only patching the return value of {@code b()}
     * would be overwritten on the next request by the cached system cookie.
     * Field {@code c} holds the account name and must not be touched.</p>
     */
    private static void forceCookieCache(Class<?> target, String cookie) {
        Object current = null;
        try {
            current = XposedHelpers.getStaticObjectField(target, "b");
        } catch (Throwable ignored) {
            // field may not exist on other versions - setStaticObjectField below will log.
        }
        if (cookie.equals(current)) {
            return;
        }
        try {
            XposedHelpers.setStaticObjectField(target, "b", cookie);
            if (!sCookieCacheLogged) {
                sCookieCacheLogged = true;
                log("cookie cache field updated: " + target.getName()
                        + ".b -> user cookie (persists for subsequent requests)");
            }
        } catch (Throwable t) {
            if (!sCookieCacheWarned) {
                sCookieCacheWarned = true;
                log("WARN: cannot update static cookie cache field "
                        + target.getName() + ".b; cookie injection only applies to the "
                        + "first request - report this (" + t + ")");
            }
        }
    }

    // ---------------------------------------------------------------------
    // Config bridge (CookieConfigProvider) with a short TTL cache
    // ---------------------------------------------------------------------

    private static Bundle config() {
        Bundle cached = sConfig;
        long now = System.currentTimeMillis();
        if (cached != null && (now - sConfigTs) < CONFIG_TTL_MS) {
            return cached;
        }
        synchronized (HookEntry.class) {
            if (sConfig != null && (System.currentTimeMillis() - sConfigTs) < CONFIG_TTL_MS) {
                return sConfig;
            }
            Bundle fresh = fetchConfig();
            sConfig = fresh;
            sConfigTs = System.currentTimeMillis();
            return fresh;
        }
    }

    private static Bundle fetchConfig() {
        Bundle fallback = new Bundle();
        fallback.putString("cookie", "");
        fallback.putString("captured_cookie", "");
        fallback.putBoolean("enable_props", ModuleConfig.DEFAULT_ENABLE_PROPS);
        fallback.putBoolean("enable_cookie", ModuleConfig.DEFAULT_ENABLE_COOKIE);
        fallback.putBoolean("force_cta", ModuleConfig.DEFAULT_FORCE_CTA);
        fallback.putString("module_version", ModuleConfig.MODULE_VERSION);
        try {
            Context ctx = appContext();
            if (ctx == null) {
                log("config: no app context yet, using defaults");
                return fallback;
            }
            Bundle b = ctx.getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_GET_CONFIG, null, null);
            if (b != null) {
                return b;
            }
            log("config: provider returned null, using defaults");
        } catch (Throwable t) {
            log("config fetch failed (is the module app installed?): " + t);
        }
        return fallback;
    }

    private static Context appContext() {
        Context cached = sAppContext;
        if (cached != null) {
            return cached;
        }
        try {
            cached = AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
        }
        sAppContext = cached;
        return cached;
    }

    private static String userCookie() {
        try {
            Bundle c = config();
            if (!c.getBoolean("enable_cookie", ModuleConfig.DEFAULT_ENABLE_COOKIE)) {
                return "";
            }
            String cookie = c.getString("cookie");
            return cookie == null ? "" : cookie.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Reports the system-account cookie that the app itself fetched to the module UI. */
    private static void reportCapturedCookie(String raw) {
        if (raw == null) {
            return;
        }
        String cookie = raw.trim();
        if (cookie.isEmpty() || cookie.equals(sLastReportedCookie)) {
            return;
        }
        try {
            Context ctx = appContext();
            if (ctx == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString("cookie", cookie);
            Bundle result = ctx.getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_SET_CAPTURED_COOKIE, null, extras);
            sLastReportedCookie = cookie;
            boolean ok = result != null && result.getBoolean("ok", false);
            log("captured system-account cookie (" + cookie.length()
                    + " chars) reported to module app, ok=" + ok);
        } catch (Throwable t) {
            log("captured cookie report failed: " + t);
        }
    }
}
