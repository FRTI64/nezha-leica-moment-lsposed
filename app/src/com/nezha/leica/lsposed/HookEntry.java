package com.nezha.leica.lsposed;

import android.content.Context;
import android.os.Bundle;

import android.app.AndroidAppHelper;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.UUID;
import java.util.Locale;
import java.util.Map;
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

    /**
     * v2.5.2 option B: legend version fallback injection variants.
     * {label, resolverClass, versionDataClass, versionEnumClass}.
     * CN mediaeditor 2.10.43: lv.j0 -> lv.d0, version enum lv.i0 (b=V2).
     * GLOBAL mediaeditor 2.10.40: hz.q0 -> hz.k0, version enum hz.p0 (b=V2).
     */
    private static final String[][] LEGEND_VARIANTS = new String[][]{
            {"CN", "lv.j0", "lv.d0", "lv.i0"},
            {"GLOBAL", "hz.q0", "hz.k0", "hz.p0"}
    };
    private static final String LEGEND_INFO_CLASS = "com.miui.mediaeditor.legendary.e";
    /** CN descriptor kept for logging/compat (GLOBAL counterpart is Lhz/k0;). */
    private static final String LEGEND_RESULT_CLASS = "Llv/d0;";

    /** Maker-note map-level safety net: {mapClass, label}; getter is a(String). */
    private static final String[][] LEGEND_MAKER_NOTE_TARGETS = new String[][]{
            {"pc.g", "CN"},
            {"mg.g", "GLOBAL"}
    };
    private static final String[] LEGEND_MAKER_NOTE_KEYS = new String[]{
            "MomentVersion", "MomentType", "MomentSubMode", "MomentPro",
            "MomentBokehDepthVersion", "depthMapVersion"
    };
    private static final String[] LEGEND_MAKER_NOTE_VALUES = new String[]{
            "2", "9", "2", "1", "4", "4"
    };

    /** v2.5.3: CreateLegendV2TaskParams (real class name, CN and GLOBAL). */
    private static final String LEGEND_CLOUD_PARAMS_CLASS =
            "com.miui.mediaeditor.aigc.cloud.internal.legend.api.entity.CreateLegendV2TaskParams";
    private static volatile String sPseudoOaid = "";
    private static volatile boolean sLegendCloudParamsLogged;
    private static volatile boolean sLegendCloudParamsWarned;

    /** v2.6.0: camera MakerNote tag-map sync (CN Bf.g / GLOBAL Ig.g). */
    private static final String[][] CAMERA_MAKERNOTE_CLASSES = new String[][]{
            {"CN", "Bf.g"},
            {"GLOBAL", "Ig.g"}
    };
    private static final String[] CAMERA_MOMENT_TAG_NAMES = new String[]{
            "MomentVersion", "MomentType", "MomentSubMode", "MomentPro",
            "MomentBokehDepthVersion"
    };
    private static final int[] CAMERA_MOMENT_TAG_IDS = new int[]{0x30, 0x31, 0x32, 0x33, 0x34};
    private static final int CAMERA_MOMENT_TAG_TYPE = 4;
    private static volatile boolean sCameraMakerNoteWarned;

    /** v2.6.1: capture legend create JSON + server response body (local only, no tokens). */
    private static final String LEGEND_CREATE_ADAPTER_CLASS =
            "com.miui.mediaeditor.aigc.cloud.internal.legend.api.entity.CreateLegendV2TaskParamsJsonAdapter";
    private static final String CLOUD_RESPONSE_BODY_CLASS = "b11.f0";
    private static volatile boolean sCloudCaptureWarned;

    /** v2.6.2: logcat-visible diagnostics tag (also used by Log.i/Log.w). */
    private static final String LOGCAT_TAG = "LeicaMoment";
    private static final String AI_AVATAR_RESP_ENTITY_CLASS =
            "com.miui.mediaeditor.aigc.cloud.internal.aiavatarv2.api.entity.AiAvatarV2ResponseEntity";
    private static final String RETROFIT_HTTP_EXCEPTION_CLASS = "l21.j";

    /** v2.7.0: redirect AI cloud base URL to CN endpoint (intl backend billing is broken). */
    private static final String CLOUD_ENDPOINT_CN = "https://api.open.ai.xiaomi.com";
    private static final String[][] CLOUD_ENDPOINT_TARGETS = new String[][]{
            {"GLOBAL", "jq.d"},
            {"CN", "nm.c"}
    };
    private static volatile String sLastCloudEndpointOld = "";
    private static volatile boolean sCloudEndpointWarned;

    /** v2.7.2: region/country SystemProperties used by the cloud-config layer. */
    private static final String SYS_PROP_CLASS = "android.os.SystemProperties";
    private static final String PROP_MIUI_BUILD_REGION = "ro.miui.build.region";
    private static final String PROP_MIUI_REGION = "ro.miui.region";
    private static final String PROP_VENDOR_MIUI_REGION = "ro.vendor.miui.region";
    private static final String PROP_PRODUCT_LOCALE_REGION = "ro.product.locale.region";
    private static final String PROP_VENDOR_RADIO_BUILD_REGION = "ro.vendor.radio.build_region";
    private static final String PROP_RIL_REGION = "ro.ril.region";
    private static final String[][] REGION_PROP_EXACT = new String[][]{
            {PROP_MIUI_BUILD_REGION, "cn"},
            {PROP_MIUI_REGION, "CN"},
            {PROP_VENDOR_MIUI_REGION, "CN"},
            {PROP_PRODUCT_LOCALE_REGION, "CN"},
            {PROP_VENDOR_RADIO_BUILD_REGION, "CN"},
            {PROP_RIL_REGION, "CN"}
    };
    private static final ThreadLocal<Boolean> sSysPropRewriteGuard = new ThreadLocal<Boolean>();
    private static final java.util.Set<String> sLoggedRegionProps = new java.util.HashSet<String>();
    private static final java.util.Set<String> sLoggedRegionRewrites = new java.util.HashSet<String>();
    private static volatile boolean sSysPropWarned;

    /** v2.7.1: simulate CN region flag used by the cloud-config layer. */
    private static final String[][] CN_REGION_CLASSES = new String[][]{
            {"GLOBAL", "r21.c"},
            {"CN", "gz0.b"}
    };
    private static final String CN_REGION_FIELD = "b";
    private static volatile Class<?> sCnRegionClass;
    private static volatile String sCnRegionLabel = "";
    private static volatile boolean sCnRegionWarned;

    private static volatile Constructor<?> sLegendCtor;
    private static volatile Object sLegendV2;
    private static volatile Object sLegendM3;
    private static volatile Object sLegendF;
    private static volatile String sLegendCacheVariant = "";
    private static volatile boolean sLegendReady;
    private static volatile boolean sLegendWarned;
    private static volatile boolean sLegendLogged;
    private static volatile boolean sMakerNoteLogged;

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
                hookCameraMakerNoteMap(lpparam);
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
            hookMakerNoteFallback(lpparam);
            hookLegendCloudParams(lpparam);
            hookLegendCreateBodyCapture(lpparam);
            hookCloudResponseBodyCapture(lpparam);
            hookAiAvatarResponseEntity(lpparam);
            hookRetrofitHttpException(lpparam);
            hookCnRegionSimulation(lpparam);
            hookSystemPropertiesRegion(lpparam);
            hookCloudEndpointRedirect(lpparam);
            log("all hook installers finished for " + pkg);
        } catch (Throwable t) {
            log("FATAL hook installation error: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    /** Dual logging: LSPosed bridge log + logcat (tag LeicaMoment). */
    private static void dualLog(String msg) {
        log(msg);
        try {
            android.util.Log.i(LOGCAT_TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    private static void logcatWarn(String msg, Throwable t) {
        try {
            android.util.Log.w(LOGCAT_TAG, msg + (t == null ? "" : (": " + t)));
        } catch (Throwable ignored) {
        }
    }

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
    // (1a.5) Camera Xiaomi MakerNote tag-map sync (v2.6.0: CN -> GLOBAL)
    // ---------------------------------------------------------------------

    /**
     * The GLOBAL MiuiCamera 6.6 maker-note tag map only registers the 5 HdrDisplay
     * tags; the CN camera 6.7 additionally registers the 5 Moment* tags
     * (0x30..0x34, type 4). Its ExifToolBuild re-serializes the maker note from the
     * parsed map, so unknown Moment tags are dropped on save. Extending the map
     * makes the global camera PRESERVE/re-serialize HAL-written Moment tags.
     * Candidate map classes: CN Bf.g, GLOBAL Ig.g. Fully fail-safe.
     */
    private static void hookCameraMakerNoteMap(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!syncCameraMakerNoteEnabled()) {
            log("camera makernote sync disabled (sync_cn_camera_makernote=off)");
            reportCameraMakerNoteTarget("none");
            return;
        }
        for (int v = 0; v < CAMERA_MAKERNOTE_CLASSES.length; v++) {
            String label = CAMERA_MAKERNOTE_CLASSES[v][0];
            String className = CAMERA_MAKERNOTE_CLASSES[v][1];
            Class<?> cls;
            try {
                cls = XposedHelpers.findClass(className, lpparam.classLoader);
            } catch (Throwable t) {
                log("camera makernote: " + label + " class " + className
                        + " not present (skip)");
                continue;
            }
            try {
                boolean extended = extendCameraMakerNoteMaps(cls, className, label);
                reportCameraMakerNoteTarget(label);
                if (extended) {
                    log("camera makernote map extended: +5 Moment tags (" + label
                            + "=" + className + ")");
                } else {
                    log("camera makernote map already contains Moment tags or was not modifiable ("
                            + label + "=" + className + ")");
                }
                return;
            } catch (Throwable t) {
                if (!sCameraMakerNoteWarned) {
                    sCameraMakerNoteWarned = true;
                    log("camera makernote map extension failed (fail-safe skip): " + t);
                }
                reportCameraMakerNoteTarget("none");
                return;
            }
        }
        reportCameraMakerNoteTarget("none");
        log("WARN: no camera makernote map class matched (CN Bf.g / GLOBAL Ig.g absent)");
    }

    private static boolean syncCameraMakerNoteEnabled() {
        try {
            return config().getBoolean("sync_cn_camera_makernote",
                    ModuleConfig.DEFAULT_SYNC_CN_CAMERA_MAKERNOTE);
        } catch (Throwable t) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean extendCameraMakerNoteMaps(Class<?> cls, String className, String label)
            throws Throwable {
        Object dObj = null;
        Object eObj = null;
        try {
            dObj = XposedHelpers.getStaticObjectField(cls, "d");
        } catch (Throwable ignored) {
        }
        try {
            eObj = XposedHelpers.getStaticObjectField(cls, "e");
        } catch (Throwable ignored) {
        }
        if (!(dObj instanceof Map) && !(eObj instanceof Map)) {
            log("camera makernote: " + className + " has no static map d/e fields");
            return false;
        }
        Map<Object, Object> dMap = (dObj instanceof Map) ? (Map<Object, Object>) dObj : null;
        Map<Object, Object> eMap = (eObj instanceof Map) ? (Map<Object, Object>) eObj : null;

        Class<?> entryClass = null;
        if (dMap != null && !dMap.isEmpty()) {
            Object first = dMap.values().iterator().next();
            if (first != null) {
                entryClass = first.getClass();
            }
        }
        if (entryClass == null && eMap != null && !eMap.isEmpty()) {
            Object first = eMap.values().iterator().next();
            if (first != null) {
                entryClass = first.getClass();
            }
        }
        if (entryClass == null) {
            String entryName = "CN".equals(label) ? "Bf.b$e" : "Ig.b$e";
            try {
                entryClass = XposedHelpers.findClass(entryName, cls.getClassLoader());
            } catch (Throwable t) {
                log("camera makernote: entry class " + entryName + " not found");
                return false;
            }
        }

        Constructor<?> ctor = null;
        Constructor<?>[] ctors = entryClass.getDeclaredConstructors();
        for (int i = 0; i < ctors.length; i++) {
            Class<?>[] ps = ctors[i].getParameterTypes();
            if (ps.length == 3 && ps[0] == String.class
                    && ps[1] == int.class && ps[2] == int.class) {
                ctor = ctors[i];
                break;
            }
        }
        if (ctor == null) {
            log("camera makernote: no (String,int,int) constructor on " + entryClass.getName());
            return false;
        }
        try {
            ctor.setAccessible(true);
        } catch (Throwable ignored) {
        }

        int added = 0;
        Object[] newEntries = new Object[CAMERA_MOMENT_TAG_IDS.length];
        int newEntryCount = 0;
        for (int i = 0; i < CAMERA_MOMENT_TAG_IDS.length; i++) {
            String name = CAMERA_MOMENT_TAG_NAMES[i];
            int id = CAMERA_MOMENT_TAG_IDS[i];
            Object entry = null;
            if (dMap != null) {
                entry = dMap.get(Integer.valueOf(id));
            }
            if (entry == null && eMap != null) {
                entry = eMap.get(name);
            }
            if (entry == null) {
                entry = ctor.newInstance(name, Integer.valueOf(id),
                        Integer.valueOf(CAMERA_MOMENT_TAG_TYPE));
                added++;
            }
            if (dMap != null && !dMap.containsKey(Integer.valueOf(id))) {
                dMap.put(Integer.valueOf(id), entry);
            }
            if (eMap != null && !eMap.containsKey(name)) {
                eMap.put(name, entry);
            }
            newEntries[newEntryCount++] = entry;
        }
        if (added == 0) {
            return false;
        }
        // Optional: append the new entries to the static array c (rebuilt).
        try {
            Object arrayObj = XposedHelpers.getStaticObjectField(cls, "c");
            if (arrayObj != null && arrayObj.getClass().isArray()) {
                int oldLen = Array.getLength(arrayObj);
                Object newArray = Array.newInstance(arrayObj.getClass().getComponentType(),
                        oldLen + newEntryCount);
                System.arraycopy(arrayObj, 0, newArray, 0, oldLen);
                for (int i = 0; i < newEntryCount; i++) {
                    Array.set(newArray, oldLen + i, newEntries[i]);
                }
                XposedHelpers.setStaticObjectField(cls, "c", newArray);
            }
        } catch (Throwable ignored) {
            // optional only
        }
        return true;
    }

    private static void reportCameraMakerNoteTarget(String target) {
        log("camera_makernote=" + target);
        try {
            Context ctx = appContext();
            if (ctx == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString("target", target);
            ctx.getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_SET_CAMERA_MAKERNOTE_TARGET, null, extras);
        } catch (Throwable t) {
            log("camera_makernote report failed: " + t);
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
                                    String traceBody = "";
                                    String lowerTracePath = path.toLowerCase(Locale.US);
                                    if (lowerTracePath.contains("aiphotocloud")
                                            || lowerTracePath.contains("legend")) {
                                        traceBody = peekResponseBodySnippet(response, 1024);
                                    }
                                    recordCloudTrace(ts + " " + httpMethod + " " + host + path
                                            + " -> " + (code > 0 ? String.valueOf(code) : "?")
                                            + " cookie=" + (cookiePresent ? "1" : "0")
                                            + (traceBody.isEmpty() ? "" : " body=" + traceBody));
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
    // (1d) Legend version fallback injection (v2.5.2: CN + GLOBAL variants)
    // ---------------------------------------------------------------------

    /**
     * Hooks the mediaeditor LegendaryVersionResolver factory (CN lv.j0 / GLOBAL
     * hz.q0) so decodeImage takes the built-in V2/M3 fallback path even when the
     * photo has NO Xiaomi maker note. Result is replaced by a crafted version-data
     * object: (version enum b = V2, legendary.e.c = M3, Boolean.TRUE, legendary.e.f).
     * Fully fail-safe: missing classes/fields/constructors are logged and skipped.
     */
    private static void hookLegendFallback(XC_LoadPackage.LoadPackageParam lpparam) {
        for (int v = 0; v < LEGEND_VARIANTS.length; v++) {
            final int variantIndex = v;
            final String label = LEGEND_VARIANTS[v][0];
            final String resolverName = LEGEND_VARIANTS[v][1];
            final String resultName = LEGEND_VARIANTS[v][2];
            Class<?> resolver;
            try {
                resolver = XposedHelpers.findClass(resolverName, lpparam.classLoader);
            } catch (Throwable t) {
                log("legend fallback: " + label + " resolver class " + resolverName
                        + " not present on this app version (skip)");
                continue;
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
                    if (!m.getReturnType().getName().equals(resultName)) {
                        continue;
                    }
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!legendFallbackEnabled()) {
                                    return;
                                }
                                Object crafted = buildLegendFallback(
                                        lpparam.classLoader, variantIndex);
                                if (crafted != null) {
                                    param.setResult(crafted);
                                    if (!sLegendLogged) {
                                        sLegendLogged = true;
                                        log("legend fallback injected: " + resolverName + "."
                                                + methodName + " -> " + resultName + " (" + label
                                                + "; V2/M3/TRUE/f; cleaned upload fallback path enabled)");
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
                } catch (Throwable t) {
                    log("legend fallback hookMethod failed: " + m.getName() + " -> " + t);
                }
            }
            if (installed > 0) {
                log("legend fallback hook installed: " + resolverName + " (" + label + ")");
                reportLegendFallbackTarget(label);
                return;
            }
        }
        reportLegendFallbackTarget("none");
        log("WARN: no legend fallback resolver matched (expected " + LEGEND_RESULT_CLASS
                + " on CN / Lhz/k0; on GLOBAL; force_legend_fallback remains enabled but inert)");
    }

    private static void reportLegendFallbackTarget(String target) {
        log("legend_fallback_target=" + target);
        try {
            Context ctx = appContext();
            if (ctx == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString("target", target);
            ctx.getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_SET_LEGEND_FALLBACK_TARGET, null, extras);
        } catch (Throwable t) {
            log("legend_fallback_target report failed: " + t);
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

    private static Object buildLegendFallback(ClassLoader loader, int variantIndex) throws Throwable {
        String label = LEGEND_VARIANTS[variantIndex][0];
        String resultName = LEGEND_VARIANTS[variantIndex][2];
        String versionEnumName = LEGEND_VARIANTS[variantIndex][3];
        if (sLegendReady && label.equals(sLegendCacheVariant) && sLegendCtor != null) {
            return sLegendCtor.newInstance(sLegendV2, sLegendM3, Boolean.TRUE, sLegendF);
        }
        synchronized (HookEntry.class) {
            if (sLegendReady && label.equals(sLegendCacheVariant) && sLegendCtor != null) {
                return sLegendCtor.newInstance(sLegendV2, sLegendM3, Boolean.TRUE, sLegendF);
            }
            Class<?> dataClass = XposedHelpers.findClass(resultName, loader);
            Class<?> versionEnum = XposedHelpers.findClass(versionEnumName, loader);
            Class<?> info = XposedHelpers.findClass(LEGEND_INFO_CLASS, loader);
            Object v2 = XposedHelpers.getStaticObjectField(versionEnum, "b"); // b = V2
            Object m3 = XposedHelpers.getStaticObjectField(info, "c"); // c = M3
            Object f;
            try {
                f = XposedHelpers.getStaticObjectField(info, "f");
            } catch (Throwable t) {
                f = XposedHelpers.getStaticObjectField(info, "d");
            }
            Constructor<?> ctor = findLegendConstructor(dataClass, versionEnum, info);
            if (ctor == null) {
                throw new NoSuchMethodException("no (" + versionEnumName
                        + ", legendary.e, Boolean, legendary.e) constructor on " + resultName);
            }
            sLegendCtor = ctor;
            sLegendV2 = v2;
            sLegendM3 = m3;
            sLegendF = f;
            sLegendCacheVariant = label;
            sLegendReady = true;
            log("legend fallback: resolved " + resultName + " constructor + "
                    + versionEnumName + ".b/legendary.e.c/f (" + label + ")");
            return ctor.newInstance(v2, m3, Boolean.TRUE, f);
        }
    }

    private static Constructor<?> findLegendConstructor(Class<?> dataClass,
                                                        Class<?> versionEnum,
                                                        Class<?> info) {
        Constructor<?>[] ctors = dataClass.getDeclaredConstructors();
        for (int i = 0; i < ctors.length; i++) {
            Constructor<?> c = ctors[i];
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length != 4) {
                continue;
            }
            if (!(ps[2] == boolean.class || ps[2] == Boolean.class)) {
                continue;
            }
            if (!acceptParam(ps[0], versionEnum) || !acceptParam(ps[1], info)
                    || !acceptParam(ps[3], info)) {
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
    // (1e) Maker-note map safety net (CN pc.g / GLOBAL mg.g)
    // ---------------------------------------------------------------------

    private static void hookMakerNoteFallback(XC_LoadPackage.LoadPackageParam lpparam) {
        int installed = 0;
        for (int t = 0; t < LEGEND_MAKER_NOTE_TARGETS.length; t++) {
            final String className = LEGEND_MAKER_NOTE_TARGETS[t][0];
            final String label = LEGEND_MAKER_NOTE_TARGETS[t][1];
            Class<?> mapClass;
            try {
                mapClass = XposedHelpers.findClass(className, lpparam.classLoader);
            } catch (Throwable e) {
                log("maker note fallback: " + label + " class " + className
                        + " not present (skip)");
                continue;
            }
            Method[] methods = mapClass.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                final Method m = methods[i];
                try {
                    if (!m.getName().equals("a")) {
                        continue;
                    }
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length != 1 || ps[0] != String.class) {
                        continue;
                    }
                    if (m.getReturnType() == void.class) {
                        continue;
                    }
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!legendFallbackEnabled()) {
                                    return;
                                }
                                if (param.getResult() != null) {
                                    return;
                                }
                                String key = param.args.length > 0
                                        ? (String) param.args[0] : null;
                                String synth = makerNoteFallbackValue(key);
                                if (synth == null) {
                                    return;
                                }
                                Class<?> rt = m.getReturnType();
                                if (rt == String.class) {
                                    param.setResult(synth);
                                } else if (rt == int.class || rt == Integer.class) {
                                    param.setResult(Integer.valueOf(synth));
                                } else if (rt == long.class || rt == Long.class) {
                                    param.setResult(Long.valueOf(synth));
                                } else {
                                    param.setResult(synth);
                                }
                                if (!sMakerNoteLogged) {
                                    sMakerNoteLogged = true;
                                    log("maker note fallback injected: " + key + "=" + synth
                                            + " (" + label + ")");
                                }
                            } catch (Throwable e) {
                                // fail-safe: never disturb maker-note parsing
                            }
                        }
                    });
                    installed++;
                    log("maker note fallback installed: " + className + ".a(String) (" + label + ")");
                } catch (Throwable e) {
                    log("maker note fallback hookMethod failed: " + m.getName() + " -> " + e);
                }
            }
        }
        if (installed == 0) {
            log("WARN: no maker note fallback map hooked (CN pc.g / GLOBAL mg.g absent)");
        }
    }

    private static String makerNoteFallbackValue(String key) {
        if (key == null) {
            return null;
        }
        for (int i = 0; i < LEGEND_MAKER_NOTE_KEYS.length; i++) {
            if (LEGEND_MAKER_NOTE_KEYS[i].equalsIgnoreCase(key)) {
                return LEGEND_MAKER_NOTE_VALUES[i];
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // (1f) Legend cloud create-params fill (v2.5.3) + response body snippet
    // ---------------------------------------------------------------------

    /**
     * Hooks {@code CreateLegendV2TaskParams} constructors (real class name, same on
     * CN and GLOBAL) and fills null/empty aperture/focusX/focusY/oaid at job time,
     * so the cloud create request carries valid parameters (device log showed
     * aperture=null, focusX=null, focusY=null, oaid="" -&gt; HTTP 400 / errorCode 439).
     * Fail-safe reflection only.
     */
    private static void hookLegendCloudParams(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(LEGEND_CLOUD_PARAMS_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            log("legend cloud params: class " + LEGEND_CLOUD_PARAMS_CLASS + " not present (skip)");
            return;
        }
        Constructor<?>[] ctors = cls.getDeclaredConstructors();
        int installed = 0;
        for (int i = 0; i < ctors.length; i++) {
            final Constructor<?> c = ctors[i];
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length != 9) {
                continue;
            }
            if (ps[6] != Float.class || ps[7] != Integer.class || ps[8] != Integer.class) {
                continue;
            }
            try {
                c.setAccessible(true);
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!legendCloudParamsEnabled()) {
                                return;
                            }
                            fillLegendCloudParams(param.thisObject);
                            logCreateParamsFinal(param.thisObject);
                        } catch (Throwable t) {
                            if (!sLegendCloudParamsWarned) {
                                sLegendCloudParamsWarned = true;
                                log("legend cloud params fill failed (fail-safe skip): " + t);
                            }
                        }
                    }
                });
                installed++;
            } catch (Throwable t) {
                log("legend cloud params constructor hook failed: " + t);
            }
        }
        if (installed == 0) {
            log("WARN: CreateLegendV2TaskParams constructor (9 params) not found");
        } else {
            log("legend cloud params hook installed: " + LEGEND_CLOUD_PARAMS_CLASS
                    + " (" + installed + " ctor)");
        }
    }

    private static boolean legendCloudParamsEnabled() {
        try {
            return config().getBoolean("force_legend_cloud_params",
                    ModuleConfig.DEFAULT_FORCE_LEGEND_CLOUD_PARAMS);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void fillLegendCloudParams(Object params) {
        if (params == null) {
            return;
        }
        boolean changed = false;
        try {
            Object aperture = XposedHelpers.getObjectField(params, "aperture");
            if (aperture == null) {
                XposedHelpers.setObjectField(params, "aperture", Float.valueOf(1.67f));
                changed = true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object focusX = XposedHelpers.getObjectField(params, "focusX");
            if (focusX == null) {
                XposedHelpers.setObjectField(params, "focusX", Integer.valueOf(2048));
                changed = true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object focusY = XposedHelpers.getObjectField(params, "focusY");
            if (focusY == null) {
                XposedHelpers.setObjectField(params, "focusY", Integer.valueOf(1536));
                changed = true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object oaid = XposedHelpers.getObjectField(params, "oaid");
            if (oaid == null || String.valueOf(oaid).trim().isEmpty()) {
                XposedHelpers.setObjectField(params, "oaid", stablePseudoOaid());
                changed = true;
            }
        } catch (Throwable ignored) {
        }
        if (changed && !sLegendCloudParamsLogged) {
            sLegendCloudParamsLogged = true;
            log("legend cloud params filled: aperture=1.67 focusX=2048 focusY=1536 oaid=pseudo-uuid"
                    + " (CreateLegendV2TaskParams)");
        }
    }

    /** Stable per-process pseudo-OAID (UUID-like; not a real advertising id). */
    private static String stablePseudoOaid() {
        String cached = sPseudoOaid;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        synchronized (HookEntry.class) {
            if (sPseudoOaid == null || sPseudoOaid.isEmpty()) {
                String raw = UUID.randomUUID().toString();
                sPseudoOaid = raw;
            }
            return sPseudoOaid;
        }
    }

    /**
     * Non-consuming response-body snippet via reflective okhttp3-style
     * {@code peekBody(1024)} (falls back to skip when unavailable/broken).
     */
    private static String peekResponseBodySnippet(Object response, int maxChars) {
        if (response == null) {
            return "";
        }
        try {
            Method peek = null;
            Method[] methods = response.getClass().getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (m.getName().equals("peekBody") && m.getParameterTypes().length == 1) {
                    Class<?> pt = m.getParameterTypes()[0];
                    if (pt == long.class || pt == Long.class || pt == int.class || pt == Integer.class) {
                        peek = m;
                        break;
                    }
                }
            }
            if (peek == null) {
                return "";
            }
            Object body = peek.invoke(response, Long.valueOf(maxChars));
            if (body == null) {
                return "";
            }
            Method stringMethod = null;
            Method[] bms = body.getClass().getMethods();
            for (int i = 0; i < bms.length; i++) {
                Method m = bms[i];
                if (m.getName().equals("string") && m.getParameterTypes().length == 0) {
                    stringMethod = m;
                    break;
                }
            }
            if (stringMethod == null) {
                return "";
            }
            Object s = stringMethod.invoke(body);
            if (!(s instanceof String)) {
                return "";
            }
            return sanitizeTraceBody((String) s, 200);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Strips control chars, collapses whitespace, redacts token-looking values, caps length. */
    private static String sanitizeTraceBody(String raw, int maxChars) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (c >= 32 && c != 127) {
                sb.append(c);
            }
        }
        String out = sb.toString().replaceAll("\\s+", " ").trim();
        // Redact obvious token-ish JSON values (defensive; keep only redacted marker).
        out = out.replaceAll("(?i)(\"?(?:cookie|passToken|serviceToken|token|signature|authorization)\"?\\s*[:=]\\s*\")[^\"]*\"", "$1***\"");
        if (out.length() > maxChars) {
            out = out.substring(0, maxChars) + "\u2026";
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // (1g) Cloud payload capture (v2.6.1: create JSON + server response body)
    // ---------------------------------------------------------------------

    /** Captures the serialized CreateLegendV2TaskParams JSON (Moshi adapter output). */
    private static void hookLegendCreateBodyCapture(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> adapter;
        try {
            adapter = XposedHelpers.findClass(LEGEND_CREATE_ADAPTER_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            log("cloud capture: " + LEGEND_CREATE_ADAPTER_CLASS + " not present (skip)");
            return;
        }
        int hooked = 0;
        Method[] methods = adapter.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            final Method m = methods[i];
            try {
                if (!m.getName().equals("toJson")) {
                    continue;
                }
                if (m.getReturnType() != String.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (!(result instanceof String)) {
                                return;
                            }
                            String json = sanitizeCaptureText((String) result, 600);
                            if (json.isEmpty()) {
                                return;
                            }
                            dualLog("legend_create_body=" + json);
                            storeCloudCapture("legend_create_body", json);
                        } catch (Throwable t) {
                            if (!sCloudCaptureWarned) {
                                sCloudCaptureWarned = true;
                                log("cloud capture (create body) failed (fail-safe): " + t);
                            }
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("cloud capture create-body hook failed: " + t);
            }
        }
        log(hooked > 0
                ? "cloud capture hook installed: CreateLegendV2TaskParamsJsonAdapter.toJson"
                : "WARN: CreateLegendV2TaskParamsJsonAdapter.toJson(String) not found");
    }

    /** Captures server response JSON via the obfuscated OkHttp ResponseBody.string() getter. */
    private static void hookCloudResponseBodyCapture(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> bodyClass;
        try {
            bodyClass = XposedHelpers.findClass(CLOUD_RESPONSE_BODY_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            log("cloud capture: response body class " + CLOUD_RESPONSE_BODY_CLASS
                    + " not present (skip)");
            return;
        }
        int hooked = 0;
        Method[] methods = bodyClass.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            final Method m = methods[i];
            try {
                if (m.getParameterTypes().length != 0) {
                    continue;
                }
                if (m.getReturnType() != String.class) {
                    continue;
                }
                if (!m.getName().equals("h") && hooked > 0) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (!(result instanceof String)) {
                                return;
                            }
                            String raw = (String) result;
                            if (raw.length() > 4000) {
                                return;
                            }
                            String lower = raw.toLowerCase(Locale.US);
                            if (!lower.contains("\"code\"") && !lower.contains("\"error\"")
                                    && !lower.contains("\"message\"") && !lower.contains("errorcode")) {
                                return;
                            }
                            String snippet = sanitizeCaptureText(raw, 600);
                            if (snippet.isEmpty()) {
                                return;
                            }
                            dualLog("cloud_resp_body=" + snippet);
                            storeCloudCapture("cloud_resp_body", snippet);
                        } catch (Throwable t) {
                            if (!sCloudCaptureWarned) {
                                sCloudCaptureWarned = true;
                                log("cloud capture (response body) failed (fail-safe): " + t);
                            }
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("cloud capture response-body hook failed: " + t);
            }
        }
        log(hooked > 0
                ? "cloud capture hook installed: ResponseBody.string getter (" + CLOUD_RESPONSE_BODY_CLASS + ")"
                : "WARN: ResponseBody string getter not found in " + CLOUD_RESPONSE_BODY_CLASS);
    }

    /** Sanitizes captured JSON: control chars stripped, token-ish values redacted, capped. */
    private static String sanitizeCaptureText(String raw, int maxChars) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (c >= 32 && c != 127) {
                sb.append(c);
            }
        }
        String out = sb.toString().replaceAll("\\s+", " ").trim();
        out = out.replaceAll(
                "(?i)(\"?(?:cookie|passToken|serviceToken|token|signature|authorization)\"?\\s*[:=]\\s*\")[^\"]*\"",
                "$1***\"");
        if (out.length() > maxChars) {
            out = out.substring(0, maxChars) + "\u2026";
        }
        return out;
    }

    /** Stores a captured payload via the provider (last 8 entries, local only). */
    private static void storeCloudCapture(String tag, String text) {
        try {
            Context ctx = appContext();
            if (ctx == null) {
                logcatWarn("cloud_capture provider skipped (no app context) tag=" + tag, null);
                return;
            }
            Bundle extras = new Bundle();
            extras.putString("tag", tag);
            extras.putString("text", text);
            Bundle result = ctx.getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_APPEND_CLOUD_CAPTURE, null, extras);
            boolean ok = result != null && result.getBoolean("ok", false);
            dualLog("cloud_capture stored tag=" + tag + " ok=" + ok
                    + " chars=" + (text == null ? 0 : text.length()));
            if (!ok) {
                logcatWarn("cloud_capture provider returned !ok tag=" + tag, null);
            }
        } catch (Throwable t) {
            logcatWarn("cloud_capture provider call failed tag=" + tag, t);
            if (!sCloudCaptureWarned) {
                sCloudCaptureWarned = true;
                log("cloud capture persist failed (fail-safe): " + t);
            }
        }
    }

    /** Logs CreateLegendV2TaskParams final field values (no full androidId). */
    private static void logCreateParamsFinal(Object params) {
        if (params == null) {
            return;
        }
        try {
            String oaid = "";
            try {
                Object v = XposedHelpers.getObjectField(params, "oaid");
                oaid = v == null ? "" : String.valueOf(v);
            } catch (Throwable ignored) {
            }
            Object aperture = null;
            Object focusX = null;
            Object focusY = null;
            Object deviceName = null;
            Object deviceType = null;
            int androidIdLen = -1;
            try {
                aperture = XposedHelpers.getObjectField(params, "aperture");
            } catch (Throwable ignored) {
            }
            try {
                focusX = XposedHelpers.getObjectField(params, "focusX");
            } catch (Throwable ignored) {
            }
            try {
                focusY = XposedHelpers.getObjectField(params, "focusY");
            } catch (Throwable ignored) {
            }
            try {
                deviceName = XposedHelpers.getObjectField(params, "deviceName");
            } catch (Throwable ignored) {
            }
            try {
                deviceType = XposedHelpers.getObjectField(params, "deviceType");
            } catch (Throwable ignored) {
            }
            try {
                Object androidId = XposedHelpers.getObjectField(params, "androidId");
                androidIdLen = androidId == null ? 0 : String.valueOf(androidId).length();
            } catch (Throwable ignored) {
            }
            String oaidPrefix = oaid.length() > 8 ? oaid.substring(0, 8) + "\u2026" : oaid;
            dualLog("create_params final: oaid=" + oaidPrefix + "(len=" + oaid.length()
                    + "), aperture=" + aperture + ", focusX=" + focusX
                    + ", focusY=" + focusY + ", deviceName=" + deviceName
                    + ", deviceType=" + deviceType + ", androidIdLen=" + androidIdLen);
        } catch (Throwable t) {
            logcatWarn("create_params final log failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // (1h) Direct response/entity hooks (v2.6.2, logcat-visible)
    // ---------------------------------------------------------------------

    /** Captures parsed AiAvatarV2 response entity (code/message/traceId). */
    private static void hookAiAvatarResponseEntity(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(AI_AVATAR_RESP_ENTITY_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            log("resp_entity: class " + AI_AVATAR_RESP_ENTITY_CLASS + " not present (skip)");
            return;
        }
        Constructor<?>[] ctors = cls.getDeclaredConstructors();
        int hooked = 0;
        for (int i = 0; i < ctors.length; i++) {
            final Constructor<?> c = ctors[i];
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length != 4) {
                continue;
            }
            if (ps[0] != int.class && ps[0] != Integer.class) {
                continue;
            }
            if (ps[1] != String.class || ps[3] != String.class) {
                continue;
            }
            try {
                c.setAccessible(true);
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object[] args = param.args;
                            Object code = args != null && args.length > 0 ? args[0] : null;
                            Object msg = args != null && args.length > 1 ? args[1] : null;
                            Object trace = args != null && args.length > 3 ? args[3] : null;
                            String msgText = msg == null ? "" : String.valueOf(msg);
                            if (msgText.length() > 200) {
                                msgText = msgText.substring(0, 200) + "\u2026";
                            }
                            String traceText = trace == null ? "" : String.valueOf(trace);
                            if (traceText.length() > 120) {
                                traceText = traceText.substring(0, 120) + "\u2026";
                            }
                            dualLog("resp_entity code=" + code + " msg=" + msgText
                                    + " trace=" + traceText);
                            storeCloudCapture("resp_entity", "code=" + code + " msg=" + msgText
                                    + " trace=" + traceText);
                        } catch (Throwable t) {
                            logcatWarn("resp_entity hook body failed", t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("resp_entity ctor hook failed: " + t);
            }
        }
        log(hooked > 0
                ? "resp_entity hook installed: " + cls.getName()
                : "WARN: AiAvatarV2ResponseEntity (int,String,Object,String) ctor not found");
    }

    /** Captures Retrofit HttpException (code/message) from l21.j. */
    private static void hookRetrofitHttpException(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(RETROFIT_HTTP_EXCEPTION_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            log("http_error: class " + RETROFIT_HTTP_EXCEPTION_CLASS + " not present (skip)");
            return;
        }
        Constructor<?>[] ctors = cls.getDeclaredConstructors();
        int hooked = 0;
        for (int i = 0; i < ctors.length; i++) {
            final Constructor<?> c = ctors[i];
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length != 1) {
                continue;
            }
            try {
                c.setAccessible(true);
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object response = param.args != null && param.args.length > 0
                                    ? param.args[0] : null;
                            int code = extractHttpCode(response);
                            String msg = extractMessageString(response, 200);
                            dualLog("http_error code=" + code + " msg=" + msg);
                            storeCloudCapture("http_error", "code=" + code + " msg=" + msg);
                        } catch (Throwable t) {
                            logcatWarn("http_error hook body failed", t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("http_error ctor hook failed: " + t);
            }
        }
        log(hooked > 0
                ? "http_error hook installed: " + cls.getName()
                : "WARN: l21.j single-arg ctor not found");
    }

    /** Picks a String-returning zero-arg getter value (prefers name containing message). */
    private static String extractMessageString(Object obj, int maxChars) {
        if (obj == null) {
            return "";
        }
        String best = "";
        try {
            Method[] methods = obj.getClass().getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (m.getParameterTypes().length != 0
                        || m.getDeclaringClass() == Object.class
                        || m.getReturnType() != String.class) {
                    continue;
                }
                try {
                    Object r = m.invoke(obj);
                    if (!(r instanceof String)) {
                        continue;
                    }
                    String s = (String) r;
                    if (m.getName().toLowerCase(Locale.US).contains("message") && !s.isEmpty()) {
                        best = s;
                        break;
                    }
                    if (best.isEmpty() && !s.isEmpty() && s.length() < 300) {
                        best = s;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (best.length() > maxChars) {
            best = best.substring(0, maxChars) + "\u2026";
        }
        return best;
    }

    // ---------------------------------------------------------------------
    // (1i) Cloud endpoint redirect (v2.7.0: intl -> CN api.open.ai.xiaomi.com)
    // ---------------------------------------------------------------------

    /**
     * Global AI cloud base URL comes from jq.d.a(...) (CN equivalent nm.c.a),
     * logged as cloudConfigProductUrl. The intl backend is broken for legendV2
     * create (missing ai_billing_record table), so rewrite the returned URL to
     * the CN endpoint. Gated by force_cn_cloud_endpoint (default true).
     * Fail-safe: never throws into the app.
     */
    private static void hookCnRegionSimulation(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!simulateCnRegionEnabled()) {
            log("cn region simulation disabled (simulate_cn_region=off)");
            reportCnRegion("off");
            return;
        }
        for (int i = 0; i < CN_REGION_CLASSES.length; i++) {
            String label = CN_REGION_CLASSES[i][0];
            String className = CN_REGION_CLASSES[i][1];
            Class<?> cls;
            try {
                cls = XposedHelpers.findClass(className, lpparam.classLoader);
            } catch (Throwable t) {
                log("cn region: " + label + " class " + className + " not present (skip)");
                continue;
            }
            try {
                XposedHelpers.setStaticBooleanField(cls, CN_REGION_FIELD, false);
                sCnRegionClass = cls;
                sCnRegionLabel = label;
                dualLog("cn region simulated: " + className + "." + CN_REGION_FIELD
                        + "=false (" + label + ")");
                dualLog("cloud config isGlobal flag patched (" + className + ")");
                reportCnRegion("simulated");
                log("cn region simulation installed: " + className + "." + label);
                return;
            } catch (Throwable t) {
                if (!sCnRegionWarned) {
                    sCnRegionWarned = true;
                    logcatWarn("cn region simulation failed (fail-safe skip)", t);
                }
                reportCnRegion("none");
                return;
            }
        }
        reportCnRegion("none");
        log("WARN: no CN region flag class matched (GLOBAL r21.c / CN gz0.b absent)");
    }

    private static boolean simulateCnRegionEnabled() {
        try {
            return config().getBoolean("simulate_cn_region",
                    ModuleConfig.DEFAULT_SIMULATE_CN_REGION);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Re-asserts the CN region flag right before the cloud config provider computes the URL. */
    private static void assertCnRegion() {
        Class<?> cls = sCnRegionClass;
        if (cls == null) {
            return;
        }
        try {
            if (simulateCnRegionEnabled()) {
                XposedHelpers.setStaticBooleanField(cls, CN_REGION_FIELD, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void reportCnRegion(String value) {
        log("cn_region=" + value);
        try {
            Context ctx = appContext();
            if (ctx == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString("value", value);
            ctx.getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_SET_CN_REGION, null, extras);
        } catch (Throwable t) {
            logcatWarn("cn_region report failed", t);
        }
    }

    /**
     * v2.7.2: hooks android.os.SystemProperties.get (1-arg and 2-arg) in the
     * mediaeditor process. Logs region/country/locale property reads (distinct,
     * with current value) and, when simulate_cn_region is ON, rewrites
     * region/country properties to CN values (runtime-only; no persisted
     * property changes). Re-asserts the cloud-config isGlobal flag too.
     */
    private static void hookSystemPropertiesRegion(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(SYS_PROP_CLASS, lpparam.classLoader);
        } catch (Throwable t) {
            log("region prop: class " + SYS_PROP_CLASS + " not found (skip)");
            return;
        }
        int hooked = 0;
        Method[] methods = cls.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            final Method m = methods[i];
            try {
                if (!m.getName().equals("get")) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 && ps.length != 2) {
                    continue;
                }
                if (ps[0] != String.class) {
                    continue;
                }
                if (ps.length == 2 && ps[1] != String.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object first = param.args != null && param.args.length > 0
                                    ? param.args[0] : null;
                            if (!(first instanceof String)) {
                                return;
                            }
                            String name = (String) first;
                            if (name == null || name.isEmpty()) {
                                return;
                            }
                            // Gate: only log/rewrite when CN region simulation is ON.
                            if (!simulateCnRegionEnabled()) {
                                return;
                            }
                            String lower = name.toLowerCase(Locale.US);
                            boolean interesting = lower.contains("region")
                                    || lower.contains("country")
                                    || lower.contains("locale");
                            if (!interesting) {
                                return;
                            }
                            if (Boolean.TRUE.equals(sSysPropRewriteGuard.get())) {
                                // Re-entrant read from inside our own logging: let original run.
                                return;
                            }
                            String originalValue = "";
                            try {
                                sSysPropRewriteGuard.set(Boolean.TRUE);
                                Object v = XposedHelpers.callStaticMethod(cls, "get", name);
                                originalValue = v == null ? "" : String.valueOf(v);
                            } catch (Throwable ignored) {
                            } finally {
                                sSysPropRewriteGuard.remove();
                            }
                            logRegionPropOnce(name, originalValue);
                            if (lower.contains("region") || lower.contains("country")) {
                                String cnValue = regionPropCnValue(lower);
                                if (cnValue != null) {
                                    param.setResult(cnValue);
                                    assertCnRegion();
                                    logRegionRewriteOnce(name, originalValue, cnValue);
                                }
                            }
                        } catch (Throwable t) {
                            if (!sSysPropWarned) {
                                sSysPropWarned = true;
                                logcatWarn("region prop hook failed (fail-safe)", t);
                            }
                        }
                    }
                });
                hooked++;
                log("sysprop region hook installed: " + SYS_PROP_CLASS + ".get params=" + ps.length);
            } catch (Throwable t) {
                log("sysprop region hook failed: " + t);
            }
        }
        if (hooked == 0) {
            log("WARN: SystemProperties.get(String[,String]) not hooked");
        }
    }

    private static void logRegionPropOnce(String name, String value) {
        synchronized (sLoggedRegionProps) {
            if (sLoggedRegionProps.size() >= 20 || sLoggedRegionProps.contains(name)) {
                return;
            }
            sLoggedRegionProps.add(name);
            dualLog("region prop: " + name + "=" + value);
        }
    }

    private static void logRegionRewriteOnce(String name, String oldValue, String newValue) {
        synchronized (sLoggedRegionRewrites) {
            if (sLoggedRegionRewrites.size() >= 20 || sLoggedRegionRewrites.contains(name)) {
                return;
            }
            sLoggedRegionRewrites.add(name);
            dualLog("region prop rewritten: " + name + " " + oldValue + " -> " + newValue);
        }
    }

    /** CN value for a region/country property (null = do not rewrite). */
    private static String regionPropCnValue(String lowerName) {
        for (int i = 0; i < REGION_PROP_EXACT.length; i++) {
            if (REGION_PROP_EXACT[i][0].toLowerCase(Locale.US).equals(lowerName)) {
                return REGION_PROP_EXACT[i][1];
            }
        }
        if (lowerName.contains("country")) {
            return "CN";
        }
        if (lowerName.contains("region")) {
            return "CN";
        }
        return null;
    }

    private static void hookCloudEndpointRedirect(XC_LoadPackage.LoadPackageParam lpparam) {
        for (int i = 0; i < CLOUD_ENDPOINT_TARGETS.length; i++) {
            final String label = CLOUD_ENDPOINT_TARGETS[i][0];
            final String className = CLOUD_ENDPOINT_TARGETS[i][1];
            Class<?> cls;
            try {
                cls = XposedHelpers.findClass(className, lpparam.classLoader);
            } catch (Throwable t) {
                log("cloud endpoint: " + label + " class " + className + " not present (skip)");
                continue;
            }
            int hooked = 0;
            Method[] methods = cls.getDeclaredMethods();
            for (int j = 0; j < methods.length; j++) {
                final Method m = methods[j];
                try {
                    if (!m.getName().equals("a") || m.getReturnType() != String.class) {
                        continue;
                    }
                    int pc = m.getParameterTypes().length;
                    if (pc != 3 && pc != 4) {
                        continue;
                    }
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Re-assert CN region right before the config URL is computed.
                            assertCnRegion();
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object result = param.getResult();
                                if (!(result instanceof String)) {
                                    return;
                                }
                                String oldUrl = (String) result;
                                String lower = oldUrl.toLowerCase(Locale.US);
                                if (lower.contains("intl.miui.com")) {
                                    if (forceCnCloudEndpointEnabled()) {
                                        param.setResult(CLOUD_ENDPOINT_CN);
                                        dualLog("cloud endpoint rewritten: " + oldUrl
                                                + " -> " + CLOUD_ENDPOINT_CN);
                                        reportCloudEndpoint(CLOUD_ENDPOINT_CN);
                                    } else {
                                        dualLog("cloud endpoint observed (intl, rewrite off): "
                                                + oldUrl);
                                        reportCloudEndpoint(oldUrl);
                                    }
                                } else {
                                    dualLog("cloud endpoint observed: " + oldUrl);
                                    reportCloudEndpoint(oldUrl);
                                }
                            } catch (Throwable t) {
                                if (!sCloudEndpointWarned) {
                                    sCloudEndpointWarned = true;
                                    logcatWarn("cloud endpoint rewrite failed (fail-safe)", t);
                                }
                            }
                        }
                    });
                    hooked++;
                    log("cloud endpoint rewrite target class=" + className + ".a (" + label + ")");
                } catch (Throwable t) {
                    log("cloud endpoint hook failed: " + m.getName() + " -> " + t);
                }
            }
            if (hooked > 0) {
                log("cloud endpoint redirect installed via " + className + " (" + label + ")");
                return;
            }
        }
        reportCloudEndpoint("none");
        log("WARN: no cloud endpoint provider matched (GLOBAL jq.d / CN nm.c absent)");
    }

    private static boolean forceCnCloudEndpointEnabled() {
        try {
            return config().getBoolean("force_cn_cloud_endpoint",
                    ModuleConfig.DEFAULT_FORCE_CN_CLOUD_ENDPOINT);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void reportCloudEndpoint(String value) {
        log("cloud_endpoint=" + value);
        try {
            Context ctx = appContext();
            if (ctx == null) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putString("url", value);
            ctx.getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_SET_CLOUD_ENDPOINT, null, extras);
        } catch (Throwable t) {
            logcatWarn("cloud_endpoint report failed", t);
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
