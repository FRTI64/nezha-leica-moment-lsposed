package com.nezha.leica.lsposed;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * Exported config bridge between the module app and the hooked
 * com.miui.mediaeditor / com.miui.gallery processes.
 *
 * <p>Required because Android 10+ blocks direct access to another app's
 * private SharedPreferences. Callers are authenticated by uid and package
 * name; there is no android:permission because a normal 3rd-party app cannot
 * obtain a signature-level permission of ours anyway, so the check is done in
 * code. Allowed: com.miui.mediaeditor, com.miui.gallery, com.android.camera
 * (the camera process only receives the gate toggle; cookie fields are
 * stripped for it) and the module app itself.</p>
 *
 * <p>v2.4.0: the system-account cookie path was removed entirely. The only
 * supported method is {@code get_config} -&gt; Bundle{cookie (user-supplied
 * only), enable_gate_bypass, enable_cookie, force_cta, module_version}. The
 * hooked process never reports any cookie back.</p>
 */
public class CookieConfigProvider extends ContentProvider {

    private static final String TAG = "LeicaMomentLSP";

    /** Package of the CN MiuiCamera mounted by the Magisk CameraOnly variant. */
    private static final String CAMERA_PACKAGE = "com.android.camera";

    private static final String[] ALLOWED_PACKAGES = new String[]{
            "com.miui.mediaeditor",
            "com.miui.gallery",
            "com.android.camera",
            "com.nezha.leica.lsposed"
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        int callingUid = Binder.getCallingUid();
        String callerPackage = resolveCallerPackage(ctx, callingUid);
        if (callerPackage == null) {
            Log.w(TAG, "CookieConfigProvider: rejected call method=" + method
                    + " from uid=" + callingUid);
            Bundle denied = new Bundle();
            denied.putBoolean("ok", false);
            denied.putString("error", "caller not allowed");
            return denied;
        }

        if (ModuleConfig.METHOD_GET_CONFIG.equals(method)) {
            Bundle config = ModuleConfig.buildConfigBundle(ctx);
            if (CAMERA_PACKAGE.equals(callerPackage)) {
                // The camera process only needs the gate-bypass toggle; never expose
                // the user cookie to it.
                config.putString("cookie", "");
            }
            return config;
        }

        Log.w(TAG, "CookieConfigProvider: unknown method " + method);
        return null;
    }

    /**
     * Resolves the calling package and verifies it is one of the allowed ones
     * (our own app, com.miui.mediaeditor, com.miui.gallery, com.android.camera).
     * Returns the matched package name, or null when the caller is not allowed.
     */
    private String resolveCallerPackage(Context ctx, int uid) {
        try {
            if (uid == Process.myUid()) {
                return ctx.getPackageName();
            }
            PackageManager pm = ctx.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null) {
                return null;
            }
            for (int i = 0; i < packages.length; i++) {
                String pkg = packages[i];
                for (int j = 0; j < ALLOWED_PACKAGES.length; j++) {
                    if (ALLOWED_PACKAGES[j].equals(pkg)) {
                        return pkg;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "CookieConfigProvider: caller check failed: " + t);
        }
        return null;
    }
}
