package com.nezha.leica.lsposed;

import android.content.Context;
import android.os.Bundle;

import android.app.AndroidAppHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
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
 *   <li>USER cookie injection: {@code go.e.b()} (CN) / {@code bs.e.b()} (GLOBAL),
 *       including the static cookie-cache field "b" fix. Only a user-supplied
 *       cookie (manual or exported from the module's own full-screen web login)
 *       is used. The app's system-account STS cookie is NEVER read, stored or
 *       reported; without a user cookie the original value passes through.</li>
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

    /** Interceptor trace targets: {class, method}. CN Lnm/f; GLOBAL Ljq/g; interceptor. */
    private static final String[][] CLOUD_TRACE_TARGETS = new String[][]{
            {"nm.f", "a"},  // CN mediaeditor 2.10.43.0.6 cookie/cloud OkHttp interceptor
            {"jq.g", "a"}   // GLOBAL mediaeditor 2.10.40.4.8-global interceptor
    };

    /**
     * Trace only cloud/AI related paths. Recorded fields: method, host, encodedPath
     * (NO query/signature), HTTP code, cookie-header-present flag. NEVER header
     * values, query params, bodies, tokens or signatures.
     */
    private static final String[] CLOUD_TRACE_PATH_FILTERS = new String[]{
            "aiphotocloud", "legend", "hardwareauth", "hardwarenonce", "auth/v1", "billing"
    };
    private static final int CLOUD_TRACE_MAX = 12;
    private static final ArrayDeque<String> sCloudTrace = new ArrayDeque<String>();
    private static volatile String sCloudTraceJoined = "";

    /** v2.5.1 option B: legend version fallback injection targets/descriptors. */
    private static final String LEGEND_RESOLVER_CLASS = "lv.j0";
    private static final String LEGEND_RESULT_CLASS_NAME = "lv.d0";
    private static final String LEGEND_RESULT_CLASS = "Llv/d0;";
    private static final String LEGEND_V2_CLASS = "lv.i0";
    private static final String LEGEND_INFO_CLASS = "com.miui.mediaeditor.legendary.e";

    private static volatile Constructor<?> sLegendCtor;
    private static volatile Object sLegendV2;
    private static volatile Object sLegendM3;
    private static volatile Object sLegendF;
    private static volatile boolean sLegendReady;
    private static volatile boolean sLegendWarned;
    private static volatile boolean sLegendLogged;

    private static volatile Bundle sConfig;
    private static volatile long sConfigTs;
    private static volatile Context sAppContext;
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
            hookCloudTrace(lpparam);
            hookAccountProbe(lpparam);
            hookLegendFallback(lpparam);
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
    // (1c) On-device cloud-request trace (compact, no tokens/query/body)
    // ---------------------------------------------------------------------

    private static void hookCloudTrace(XC_LoadPackage.LoadPackageParam lpparam) {
        int installed = 0;
        for (int i = 0; i < CLOUD_TRACE_TARGETS.length; i++) {
            final String className = CLOUD_TRACE_TARGETS[i][0];
            final String methodName = CLOUD_TRACE_TARGETS[i][1];
            try {
                Class<?> cls = XposedHelpers.findClass(className, lpparam.classLoader);
                Method[] methods = cls.getDeclaredMethods();
                boolean hookedOne = false;
                for (int j = 0; j < methods.length; j++) {
                    final Method m = methods[j];
                    if (!m.getName().equals(methodName)) {
                        continue;
                    }
                    if (m.getParameterTypes().length != 1) {
                        continue;
                    }
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object response = param.getResult();
                                    Object chain = param.args.length > 0 ? param.args[0] : null;
                                    String url = findUrlInObject(chain);
                                    if (url == null) {
                                        return;
                                    }
                                    String host = null;
                                    String path = null;
                                    try {
                                        URI uri = URI.create(url);
                                        host = uri.getHost();
                                        path = uri.getRawPath();
                                    } catch (Throwable ignored) {
                                    }
                                    if (host == null || path == null) {
                                        return;
                                    }
                                    String lowerHost = host.toLowerCase(Locale.US);
                                    if (!lowerHost.contains("xiaomi") && !lowerHost.contains("mi.com")) {
                                        return;
                                    }
                                    String lowerPath = path.toLowerCase(Locale.US);
                                    boolean worthy = false;
                                    for (int k = 0; k < CLOUD_TRACE_PATH_FILTERS.length; k++) {
                                        if (lowerPath.contains(CLOUD_TRACE_PATH_FILTERS[k])) {
                                            worthy = true;
                                            break;
                                        }
                                    }
                                    if (!worthy) {
                                        return;
                                    }
                                    String httpMethod = findHttpMethodInObject(chain, 2);
                                    if (httpMethod == null) {
                                        httpMethod = "?";
                                    }
                                    int code = extractHttpCode(response);
                                    boolean cookiePresent = hasCookieHeaderInObject(chain, 2);
                                    String ts;
                                    try {
                                        ts = new SimpleDateFormat("HH:mm:ss", Locale.US)
                                                .format(new Date());
                                    } catch (Throwable t) {
                                        ts = "?";
                                    }
                                    recordCloudTrace(ts + " " + httpMethod + " " + host + path
                                            + " -> " + (code > 0 ? String.valueOf(code) : "?")
                                            + " cookie=" + (cookiePresent ? "1" : "0"));
                                } catch (Throwable t) {
                                    // never disturb the network path
                                }
                            }
                        });
                        hookedOne = true;
                        installed++;
                        log("cloud trace hook installed: " + className + "." + m.getName()
                                + "(" + m.getParameterTypes()[0].getSimpleName() + ")");
                    } catch (Throwable t) {
                        log("cloud trace hookMethod failed: " + className + "." + methodName + " -> " + t);
                    }
                }
                if (!hookedOne) {
                    log("cloud trace target has no matching method: " + className + "." + methodName);
                }
            } catch (Throwable t) {
                log("cloud trace class not present (normal on other app versions): "
                        + className + "." + methodName + " -> " + t);
            }
        }
        if (installed == 0) {
            log("WARN: no cloud trace interceptor hook installed; request trace unavailable");
        }
    }

    private static void recordCloudTrace(String line) {
        String joined;
        synchronized (sCloudTrace) {
            sCloudTrace.addLast(line);
            while (sCloudTrace.size() > CLOUD_TRACE_MAX) {
                sCloudTrace.removeFirst();
            }
            StringBuilder sb = new StringBuilder();
            for (String l : sCloudTrace) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(l);
            }
            joined = sb.toString();
            sCloudTraceJoined = joined;
        }
        log("cloud_trace " + line);
        try {
            Context ctx = appContext();
            if (ctx == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString("trace", joined);
            ctx.getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_SET_CLOUD_TRACE, null, extras);
        } catch (Throwable t) {
            log("cloud_trace persist failed: " + t);
        }
    }

    /** Finds a URL-looking string in the object graph (depth-limited, reflection only). */
    private static String findUrlInObject(Object root) {
        if (root == null) {
            return null;
        }
        String self = safeToString(root);
        if (isUrlLike(self)) {
            return extractUrl(self);
        }
        Class<?> cls = root.getClass();
        Method[] methods = cls.getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (m.getParameterTypes().length != 0) {
                continue;
            }
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            try {
                Object result = m.invoke(root);
                if (result == null || result == root) {
                    continue;
                }
                String rs = safeToString(result);
                if (isUrlLike(rs)) {
                    return extractUrl(rs);
                }
                if (!(result instanceof String)) {
                    Method[] inner = result.getClass().getMethods();
                    for (int j = 0; j < inner.length; j++) {
                        Method im = inner[j];
                        if (im.getParameterTypes().length != 0
                                || im.getDeclaringClass() == Object.class) {
                            continue;
                        }
                        try {
                            Object ir = im.invoke(result);
                            String irs = safeToString(ir);
                            if (isUrlLike(irs)) {
                                return extractUrl(irs);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String safeToString(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return String.valueOf(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isUrlLike(String s) {
        if (s == null || s.length() > 4096) {
            return false;
        }
        return s.contains("http://") || s.contains("https://");
    }

    private static String extractUrl(String s) {
        int idx = s.indexOf("http://");
        int httpsIdx = s.indexOf("https://");
        if (idx < 0 || (httpsIdx >= 0 && httpsIdx < idx)) {
            idx = httpsIdx;
        }
        if (idx < 0) {
            return null;
        }
        int end = idx;
        while (end < s.length()) {
            char c = s.charAt(end);
            if (c == ' ' || c == '"' || c == '\'' || c == ')' || c == '}' || c == ',' || c == '\n'
                    || c == '\r' || c == '\t') {
                break;
            }
            end++;
        }
        if (end <= idx + 8) {
            return null;
        }
        String url = s.substring(idx, end);
        return url.length() > 2048 ? url.substring(0, 2048) : url;
    }

    /** Finds an HTTP method string (GET/POST/...) in the request object graph. */
    private static String findHttpMethodInObject(Object root, int depth) {
        if (root == null || depth < 0) {
            return null;
        }
        Method[] methods = root.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (m.getParameterTypes().length != 0
                    || m.getDeclaringClass() == Object.class) {
                continue;
            }
            try {
                Object r = m.invoke(root);
                if (!(r instanceof String)) {
                    continue;
                }
                String v = ((String) r).trim();
                if (v.equals("GET") || v.equals("POST") || v.equals("PUT")
                        || v.equals("DELETE") || v.equals("PATCH") || v.equals("HEAD")
                        || v.equals("OPTIONS")) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (m.getParameterTypes().length != 0
                    || m.getDeclaringClass() == Object.class) {
                continue;
            }
            try {
                Object r = m.invoke(root);
                if (r != null && r != root && !(r instanceof String)) {
                    String nested = findHttpMethodInObject(r, depth - 1);
                    if (nested != null) {
                        return nested;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Extracts the HTTP status code from an OkHttp-like response object. */
    private static int extractHttpCode(Object response) {
        if (response == null) {
            return -1;
        }
        Method[] methods = response.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (m.getParameterTypes().length != 0
                    || m.getDeclaringClass() == Object.class) {
                continue;
            }
            Class<?> rt = m.getReturnType();
            if (rt != int.class && rt != Integer.class) {
                continue;
            }
            try {
                Object r = m.invoke(response);
                if (r instanceof Integer) {
                    int v = ((Integer) r).intValue();
                    if (v >= 100 && v <= 599) {
                        return v;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** True when the request object graph contains a Cookie header (value never stored). */
    private static boolean hasCookieHeaderInObject(Object root, int depth) {
        if (root == null || depth < 0) {
            return false;
        }
        Method[] methods = root.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (m.getParameterTypes().length != 0
                    || m.getDeclaringClass() == Object.class) {
                continue;
            }
            try {
                Object r = m.invoke(root);
                String rs = safeToString(r);
                if (rs != null && rs.contains("Cookie")) {
                    return true;
                }
                if (r != null && !(r instanceof String) && depth > 0) {
                    if (hasCookieHeaderInObject(r, depth - 1)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // (1d) Legend version fallback injection (v2.5.1 option B, default on)
    // ---------------------------------------------------------------------

    /**
     * Hooks the mediaeditor LegendaryVersionResolver factory so decodeImage takes
     * the V2/M3 built-in fallback path even when the photo has NO Xiaomi maker
     * note: the result of {@code lv.j0.a(..)} / {@code lv.j0.b(..)} is replaced by
     * a crafted {@code lv.d0(lv.i0.b (V2), legendary.e.c (M3), Boolean.TRUE, legendary.e.f)} instance. Fully fail-safe: any missing class/field/method is
     * logged and skipped, never crashing the app.
     */
    private static void hookLegendFallback(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> resolver;
        try {
            resolver = XposedHelpers.findClass(LEGEND_RESOLVER_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            log("legend fallback: class " + LEGEND_RESOLVER_CLASS
                    + " not present on this version (skip)");
            return;
        }
        int installed = 0;
        Method[] methods = resolver.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            final Method m = methods[i];
            try {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                final String methodName = m.getName();
                Class<?>[] ps = m.getParameterTypes();
                boolean isA = methodName.equals("a") && ps.length == 1;
                boolean isB = methodName.equals("b") && ps.length == 2
                        && ps[0] == String.class;
                if (!isA && !isB) {
                    continue;
                }
                if (!m.getReturnType().getName().equals(LEGEND_RESULT_CLASS_NAME)) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!legendFallbackEnabled()) {
                                return;
                            }
                            Object crafted = buildLegendFallback(lpparam.classLoader);
                            if (crafted != null) {
                                param.setResult(crafted);
                                if (!sLegendLogged) {
                                    sLegendLogged = true;
                                    log("legend fallback injected: " + LEGEND_RESOLVER_CLASS
                                            + "." + methodName + " -> " + LEGEND_RESULT_CLASS
                                            + " (V2/M3/TRUE/f; cleaned upload fallback path enabled)");
                                }
                            }
                        } catch (Throwable t) {
                            if (!sLegendWarned) {
                                sLegendWarned = true;
                                log("legend fallback injection failed (fail-safe skip): " + t);
                            }
                        }
                    }
                });
                installed++;
                log("legend fallback hook installed: " + LEGEND_RESOLVER_CLASS + "."
                        + methodName + " -> " + LEGEND_RESULT_CLASS);
            } catch (Throwable t) {
                log("legend fallback hookMethod failed: " + m.getName() + " -> " + t);
            }
        }
        if (installed == 0) {
            log("WARN: no legend fallback target hooked (lv.j0.a/b shape differs on this version)");
        }
    }

    private static boolean legendFallbackEnabled() {
        try {
            return config().getBoolean("force_legend_fallback",
                    ModuleConfig.DEFAULT_FORCE_LEGEND_FALLBACK);
        } catch (Throwable t) {
            return true;
        }
    }

    private static Object buildLegendFallback(ClassLoader loader) throws Throwable {
        if (sLegendReady && sLegendCtor != null) {
            return sLegendCtor.newInstance(sLegendV2, sLegendM3, Boolean.TRUE, sLegendF);
        }
        synchronized (HookEntry.class) {
            if (sLegendReady && sLegendCtor != null) {
                return sLegendCtor.newInstance(sLegendV2, sLegendM3, Boolean.TRUE, sLegendF);
            }
            Class<?> d0 = XposedHelpers.findClass(LEGEND_RESULT_CLASS_NAME, loader);
            Class<?> i0 = XposedHelpers.findClass(LEGEND_V2_CLASS, loader);
            Class<?> info = XposedHelpers.findClass(LEGEND_INFO_CLASS, loader);
            Object v2 = XposedHelpers.getStaticObjectField(i0, "b"); // lv.i0.b = V2
            Object m3 = XposedHelpers.getStaticObjectField(info, "c"); // legendary.e.c = M3
            Object f;
            try {
                f = XposedHelpers.getStaticObjectField(info, "f");
            } catch (Throwable t) {
                f = XposedHelpers.getStaticObjectField(info, "d"); // fallback constant
            }
            Constructor<?> ctor = findLegendConstructor(d0, i0, info);
            if (ctor == null) {
                throw new NoSuchMethodException("lv.d0 has no (lv.i0, legendary.e, Boolean, legendary.e) constructor");
            }
            sLegendCtor = ctor;
            sLegendV2 = v2;
            sLegendM3 = m3;
            sLegendF = f;
            sLegendReady = true;
            log("legend fallback: resolved lv.d0 constructor + V2/M3/f enums");
            return ctor.newInstance(v2, m3, Boolean.TRUE, f);
        }
    }

    private static Constructor<?> findLegendConstructor(Class<?> d0, Class<?> i0, Class<?> info) {
        Constructor<?>[] ctors = d0.getDeclaredConstructors();
        for (int i = 0; i < ctors.length; i++) {
            Constructor<?> c = ctors[i];
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length != 4) {
                continue;
            }
            if (!(ps[2] == boolean.class || ps[2] == Boolean.class)) {
                continue;
            }
            if (!acceptParam(ps[0], i0) || !acceptParam(ps[1], info) || !acceptParam(ps[3], info)) {
                continue;
            }
            try {
                c.setAccessible(true);
            } catch (Throwable ignored) {
            }
            return c;
        }
        return null;
    }

    private static boolean acceptParam(Class<?> param, Class<?> valueClass) {
        if (param == null || valueClass == null) {
            return false;
        }
        return param.equals(valueClass) || param.isAssignableFrom(valueClass);
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

    /**
     * Returns the user-supplied cookie to inject (manual paste or the cookie
     * exported by the module's own full-screen web login).
     *
     * <p>The app's system-account STS cookie is deliberately NOT used: when no
     * user cookie is configured this returns an empty string and the original
     * value is passed through untouched.</p>
     */
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
}
