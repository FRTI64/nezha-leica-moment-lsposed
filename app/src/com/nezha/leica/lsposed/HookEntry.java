package com.nezha.leica.lsposed;

import android.content.Context;
import android.os.Bundle;

import android.app.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Legacy Xposed entry ({@code assets/xposed_init} -&gt; com.nezha.leica.lsposed.HookEntry).
 *
 * <p>v2.0.0 "no-props" mode: the module does NOT modify or spoof any system
 * property (neither Magisk resetprop nor android.os.SystemProperties hooks).
 * Instead, in the {@code com.miui.mediaeditor} process it directly hooks the
 * app's availability predicate methods and makes them return true:</p>
 * <ul>
 *   <li>CN 2.10.43.0.6: {@code rt.a.g()Z}, {@code ah0.b.g()Z}</li>
 *   <li>GLOBAL 2.10.40.4.8-global: {@code mx.a.g()Z}, {@code r21.e.b()Z}</li>
 * </ul>
 * <p>These are the chokepoints used by MediaEditorProviderForGallery for
 * {@code method_is_legend_available}; the xm/传奇 variant predicates
 * ({@code bx.t.a()}, {@code ft.s.a()}) are intentionally NOT hooked so that
 * variant naturally keeps returning false.</p>
 *
 * <p>Also kept from v1.x:</p>
 * <ol>
 *   <li>Xiaomi STS cookie injection: {@code go.e.b()} (CN) / {@code bs.e.b()}
 *       (GLOBAL), including the static cookie-cache field "b" fix.</li>
 *   <li>AccountUtil login probe {@code ga0.e.a(Context)} /
 *       {@code be0.e.a(Context)} for devices without a system Xiaomi account.</li>
 *   <li>CTA is never forced - it is a normal user-consented privacy dialog and
 *       the entry visibility is controlled by the predicates above.</li>
 * </ol>
 *
 * <p>{@code com.miui.gallery} is Flutter + Rust (Dart AOT + Rust via
 * flutter_rust_bridge): Dart/Rust code cannot be hooked from LSPosed, and in
 * no-props mode the gallery process installs NO hooks. This is fine for the
 * legend path because the capability probe runs through the mediaeditor
 * provider (Java), where the predicate hooks live. The gallery capability cache
 * may need to be cleared once after enabling the module.</p>
 *
 * <p>v2.1.0 adds the camera (capture) part for the CN MiuiCamera APK mounted by
 * the Magisk "CameraOnly" variant ({@code com.android.camera} 6.7.000280.0).
 * Still zero property spoofing: two Java gates are forced to true -
 * {@code LegendaryEnter.support()Z} (mode 0x100 徕卡一瞬 entry visibility) and
 * static {@code Te.c.W()Z} (isLeicaCustomize: Leica UI/branding, 15+ callers),
 * plus a defensive fallback {@code h5()Z} on the obfuscated device-config class.
 * Vendor camera HAL legendary capabilities and the runtime {@code legend_mode}
 * SDK cannot be added by Java hooks (documented limitation).</p>
 *
 * <p>Every hook installation and every hook body is wrapped in try/catch so a
 * version mismatch can never crash the target app.</p>
  */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "LeicaMomentLSP: ";

    private static final String PKG_EDITOR = "com.miui.mediaeditor";
    private static final String PKG_GALLERY = "com.miui.gallery";
    /** CN MiuiCamera APK mounted by the Magisk "CameraOnly" variant (no props). */
    private static final String PKG_CAMERA = "com.android.camera";

    /** How long a fetched config Bundle is considered fresh (toggles apply without restart). */
    private static final long CONFIG_TTL_MS = 8000L;

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


    /**
     * Availability predicates forced to true: {class, method}, both static.
     * Selected by version at runtime; missing targets are logged and skipped.
     * CN 2.10.43.0.6: rt.a.g()Z + ah0.b.g()Z.
     * GLOBAL 2.10.40.4.8-global: mx.a.g()Z + r21.e.b()Z.
     * bx.t.a()/ft.s.a() (xm/传奇 variant) are deliberately NOT hooked.
     */
    private static final String[][] GATE_PREDICATE_TARGETS = new String[][]{
            {"rt.a", "g"},   // CN mediaeditor 2.10.43.0.6
            {"ah0.b", "g"},  // CN mediaeditor 2.10.43.0.6
            {"mx.a", "g"},   // GLOBAL mediaeditor 2.10.40.4.8-global
            {"r21.e", "b"}   // GLOBAL mediaeditor 2.10.40.4.8-global (static)
    };


    /**
     * CN MiuiCamera 6.7.000280.0 camera gate hooks: {class, method}. Forced to
     * true only (no property reads/writes):
     * H1 LegendaryEnter.support()Z - makes mode 0x100 徕卡一瞬 appear in the
     *    mode list (u3/a.a filter calls entry.support() on every entry);
     * H2 Te.c.W()Z (static) - isLeicaCustomize backing Leica UI/branding and
     *    15+ call sites; bypasses the cached ro.theme_customize=="LCC" check.
     */
    private static final String[][] CAMERA_GATE_TARGETS = new String[][]{
            {"com.android.camera.features.mode.legendary.LegendaryEnter", "support"},
            {"Te.c", "W"}
    };

    /**
     * H3 defensive fallback: obfuscated device-config implementation that backs
     * LegendaryEnter.support() via Te/c.e and simply calls Te/c.W(). The class
     * name is a 19-char R8 name (encoded as unicode escapes for encoding safety);
     * resolution failure is expected on other camera versions and is harmless.
     */
    private static final String CAMERA_FALLBACK_CLASS =
            "\ubf93\ubf9f\ubf9d\ubfde\ubf9d\ubf99\ubfde\ubf94\ubf95\ubf86"
            + "\ubf99\ubf93\ubf95\ubfde\ubfbe\ubf95\ubf8a\ubf98\ubf91";
    private static final String CAMERA_FALLBACK_METHOD = "h5";

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
        if (!PKG_EDITOR.equals(pkg) && !PKG_GALLERY.equals(pkg) && !PKG_CAMERA.equals(pkg)) {
            return;
        }
        try {
            log("handleLoadPackage: pkg=" + pkg + " process=" + lpparam.processName);
            if (PKG_CAMERA.equals(pkg)) {
                // CN MiuiCamera mounted by the Magisk CameraOnly variant: force only the
                // app's own Java gates; no property reads or writes anywhere.
                hookCameraGates(lpparam);
                log("camera: all hook installers finished for " + pkg);
                return;
            }
            if (!PKG_EDITOR.equals(pkg)) {
                // com.miui.gallery is Flutter + Rust: Dart AOT libapp.so + Rust
                // libapp_gallery.so (flutter_rust_bridge). Dart/Rust code cannot be
                // hooked from LSPosed, and no-props mode installs nothing here by
                // design; the capability probe goes through the mediaeditor provider
                // (Java), which is where the predicate hooks live.
                log("gallery: no hooks in no-props mode (Flutter/Rust; capability probe runs in mediaeditor)");
                return;
            }
            hookGatePredicates(lpparam);
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
    // (1) Availability predicate hooks (no-props mode)
    // ---------------------------------------------------------------------

    /**
     * Forces the app's legend availability predicates to return true.
     *
     * <p>This replaces the old property-spoofing layer: no system property is
     * touched. The predicates are the chokepoints behind
     * MediaEditorProviderForGallery {@code method_is_legend_available} -
     * CN {@code rt.a.g()Z} + {@code ah0.b.g()Z}; GLOBAL {@code mx.a.g()Z} +
     * static {@code r21.e.b()Z}.</p>
     */
    private static void hookGatePredicates(XC_LoadPackage.LoadPackageParam lpparam) {
        int installed = 0;
        for (int i = 0; i < GATE_PREDICATE_TARGETS.length; i++) {
            final String className = GATE_PREDICATE_TARGETS[i][0];
            final String methodName = GATE_PREDICATE_TARGETS[i][1];
            try {
                XposedHelpers.findAndHookMethod(className, lpparam.classLoader, methodName,
                        new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                if (!gateBypassEnabled()) {
                                    // Toggle off: behave exactly like the original predicate.
                                    return XposedBridge.invokeOriginalMethod(
                                            param.method, param.thisObject, param.args);
                                }
                                return Boolean.TRUE;
                            }
                        });
                installed++;
                log("predicate hook installed: " + className + "." + methodName + "()Z -> true");
            } catch (Throwable t) {
                log("predicate target not present (normal on other app versions): "
                        + className + "." + methodName + " -> " + t);
            }
        }
        if (installed == 0) {
            log("WARN: no availability predicate hook installed on this mediaeditor version; "
                    + "gate bypass inactive");
        }
    }

    private static boolean gateBypassEnabled() {
        try {
            return config().getBoolean("enable_gate_bypass",
                    ModuleConfig.DEFAULT_ENABLE_GATE_BYPASS);
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------------------------------------------------------------
    // (1b) Camera (capture) gate hooks - CN MiuiCamera via Magisk CameraOnly
    // ---------------------------------------------------------------------

    private static void hookCameraGates(XC_LoadPackage.LoadPackageParam lpparam) {
        int installed = 0;
        for (int i = 0; i < CAMERA_GATE_TARGETS.length; i++) {
            final String className = CAMERA_GATE_TARGETS[i][0];
            final String methodName = CAMERA_GATE_TARGETS[i][1];
            try {
                XposedHelpers.findAndHookMethod(className, lpparam.classLoader, methodName,
                        forceTrueReplacement());
                installed++;
                log("camera hook installed: " + className + "." + methodName + "()Z -> true");
            } catch (Throwable t) {
                log("camera hook target not present (normal on other camera versions): "
                        + className + "." + methodName + " -> " + t);
            }
        }
        // H3 fallback: obfuscated device-config implementation (h5 -> Te/c.W()).
        try {
            XposedHelpers.findAndHookMethod(CAMERA_FALLBACK_CLASS, lpparam.classLoader,
                    CAMERA_FALLBACK_METHOD, forceTrueReplacement());
            installed++;
            log("camera hook installed: " + CAMERA_FALLBACK_CLASS + "."
                    + CAMERA_FALLBACK_METHOD + "()Z -> true (fallback)");
        } catch (Throwable t) {
            log("camera fallback hook not available (normal; primary gates cover it): "
                    + t.getClass().getSimpleName());
        }
        if (installed == 0) {
            log("WARN: no camera gate hook installed on this camera version; "
                    + "Leica mode/UI may stay hidden");
        }
    }

    /** XC_MethodReplacement returning true when the gate toggle is on, original otherwise. */
    private static XC_MethodReplacement forceTrueReplacement() {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                if (!gateBypassEnabled()) {
                    return XposedBridge.invokeOriginalMethod(
                            param.method, param.thisObject, param.args);
                }
                return Boolean.TRUE;
            }
        };
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
        fallback.putBoolean("enable_gate_bypass", ModuleConfig.DEFAULT_ENABLE_GATE_BYPASS);
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
