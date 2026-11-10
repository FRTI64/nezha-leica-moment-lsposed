package com.nezha.leica.lsposed;

import android.os.Build;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hello-world LSPosed module entry point (legacy Xposed API 82).
 *
 * LSPosed loads this class in every scoped process because it is listed in
 * assets/xposed_init. It only logs, so it is safe to enable on any app.
 */
public class HelloHookEntry implements IXposedHookLoadPackage {

    public static final String TAG = "LeicaMomentLSP";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = (lpparam == null ? "null" : lpparam.packageName);
        XposedBridge.log(TAG + ": hello from Gradle-free build, package=" + pkg
                + " sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.DEVICE);

        // Prove that the module class loader can see the app's/process's classes.
        try {
            ClassLoader cl = (lpparam == null ? null : lpparam.classLoader);
            Class<?> sp = Class.forName("android.os.SystemProperties", false, cl);
            XposedBridge.log(TAG + ": resolved " + sp.getName()
                    + " region=" + SystemPropertiesGet("ro.miui.build.region", "?")
                    + " theme_customize=" + SystemPropertiesGet("ro.theme_customize", "?"));
        } catch (Throwable t) {
            // Never let the demo module crash the target process.
            XposedBridge.log(TAG + ": class lookup failed: " + t);
        }
    }

    /** Tiny reflective SystemProperties helper; avoids compile-time hidden-API issues. */
    private static String SystemPropertiesGet(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, def);
            return String.valueOf(v);
        } catch (Throwable t) {
            return def;
        }
    }
}
