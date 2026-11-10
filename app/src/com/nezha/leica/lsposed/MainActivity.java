package com.nezha.leica.lsposed;

import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Module UI: Xiaomi account WebView login / cookie capture + manual paste +
 * toggles. Fully programmatic (no XML layouts, no app resources).
 */
public class MainActivity extends Activity {

    private static final String TAG = "LeicaMomentLSP";

    private static final String LOGIN_URL =
            "https://account.xiaomi.com/pass/serviceLogin?sid=i.mi.com&callback="
                    + "https%3A%2F%2Fi.mi.com%2Fsts%3Fsign%3Dx%26followup%3Dhttps%253A%252F%252Fi.mi.com%252Fvip";

    /**
     * URLs queried with CookieManager.getCookie() and merged (dedupe by name,
     * first occurrence wins) into one "name=value; name=value" header.
     */
    private static final String[] COOKIE_URLS = new String[]{
            "https://account.xiaomi.com",
            "https://i.mi.com",
            "https://api.open.ai.xiaomi.com",
            "https://xiaomi.com"
    };

    private static final String HELP_TEXT = buildHelpText();

    private TextView statusView;
    private TextView capturedView;
    private EditText cookieEdit;
    private WebView webView;
    private CheckBox cbGate;
    private CheckBox cbCookie;
    private CheckBox cbCta;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(buildUi());
            loadConfig();
        } catch (Throwable t) {
            Log.e(TAG, "onCreate failed", t);
            setStatus("界面初始化失败: " + t + "\n如果系统 WebView 被禁用，请先在系统设置中启用 Android System WebView。");
        }
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("徕卡一瞬 · LSPosed 模块  v" + ModuleConfig.MODULE_VERSION);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("作用域：com.miui.mediaeditor（必选）/ com.miui.gallery（可选）");
        subtitle.setTextSize(12);
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setText("就绪。请按下面 ①→⑤ 顺序操作。");
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(10), 0, dp(2));
        root.addView(statusView);

        // ① Xiaomi account login + cookie capture
        root.addView(sectionTitle("① 小米账号登录（抓取 Cookie）"));
        TextView loginHint = new TextView(this);
        loginHint.setTextSize(12);
        loginHint.setText("在下方网页里登录你的小米账号。登录成功后点「读取登录Cookie」。\n"
                + "本模块不会上传 Cookie；Cookie 只保存在本机模块私有存储中，供 LSPosed 挂钩进程读取。");
        root.addView(loginHint);

        webView = new WebView(this);
        try {
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setUserAgentString(ws.getUserAgentString()
                    + " LeicaMomentLSP/" + ModuleConfig.MODULE_VERSION);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(webView, true);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    setStatus("页面已加载：" + url + "\n若已登录，请点「读取登录Cookie」。");
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "webview setup failed", t);
        }
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380));
        webLp.topMargin = dp(8);
        webLp.bottomMargin = dp(6);
        root.addView(webView, webLp);
        try {
            webView.loadUrl(LOGIN_URL);
        } catch (Throwable t) {
            setStatus("网页加载失败：" + t + "\n可直接在下方粘贴 Cookie。");
        }

        // ② Cookie field + action buttons
        root.addView(sectionTitle("② Cookie 注入"));
        cookieEdit = new EditText(this);
        cookieEdit.setHint("在此粘贴 Cookie 头，例如：cUserId=...; serviceToken=...; userId=...; passToken=...");
        cookieEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cookieEdit.setMinLines(4);
        cookieEdit.setMaxLines(8);
        cookieEdit.setGravity(Gravity.TOP | Gravity.START);
        cookieEdit.setTextSize(12);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120));
        editLp.topMargin = dp(6);
        root.addView(cookieEdit, editLp);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, dp(8), 0, 0);

        Button btnCapture = actionButton("读取登录Cookie", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCaptureClick();
            }
        });
        Button btnSave = actionButton("保存设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSaveClick();
            }
        });
        Button btnClear = actionButton("清空", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClearClick();
            }
        });
        LinearLayout.LayoutParams oneThird = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        LinearLayout.LayoutParams oneThirdMiddle = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        oneThirdMiddle.leftMargin = dp(4);
        oneThirdMiddle.rightMargin = dp(4);
        LinearLayout.LayoutParams oneThirdLast = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        buttonRow.addView(btnCapture, oneThird);
        buttonRow.addView(btnSave, oneThirdMiddle);
        buttonRow.addView(btnClear, oneThirdLast);
        root.addView(buttonRow);

        // ③ Captured system-account cookie
        root.addView(sectionTitle("③ 系统账号捕获的 Cookie（通常更可靠）"));
        capturedView = new TextView(this);
        capturedView.setTextSize(12);
        capturedView.setPadding(0, 0, 0, dp(6));
        root.addView(capturedView);

        Button btnUseCaptured = actionButton("把捕获的 Cookie 填入上方输入框", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onUseCapturedClick();
            }
        });
        root.addView(btnUseCaptured);

        TextView sysLoginHint = new TextView(this);
        sysLoginHint.setTextSize(12);
        sysLoginHint.setPadding(0, dp(12), 0, 0);
        sysLoginHint.setText("如果本机还没有登录系统小米账号，可点下方按钮调起系统的「小米账号」登录界面"
                + "（部分 ROM 可能直接打开小米账号页面）。登录成功后打开相册→编辑一次并触发云端/徕卡功能，"
                + "模块会自动捕获 App 自己取到的 STS Cookie，再回到本页点「把捕获的 Cookie 填入上方输入框」并保存。"
                + "系统账号 Cookie 比网页登录 Cookie 更可靠。");
        root.addView(sysLoginHint);

        Button btnSystemLogin = actionButton("登录系统小米账号", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSystemAccountLoginClick();
            }
        });
        root.addView(btnSystemLogin);

        // ④ Toggles
        root.addView(sectionTitle("④ 开关"));
        cbGate = new CheckBox(this);
        cbGate.setText("启用门禁绕过（直接 hook 判定方法返回 true，不修改/伪装任何系统属性）");
        cbGate.setTextSize(14);
        root.addView(cbGate);

        cbCookie = new CheckBox(this);
        cbCookie.setText("启用 Cookie 注入（用上方 Cookie 替换 App 取到的 STS Cookie；留空则按原样）");
        cbCookie.setTextSize(14);
        root.addView(cbCookie);

        cbCta = new CheckBox(this);
        cbCta.setText("强制 CTA 已同意（有隐私风险；本版本有意不强制，仅记录日志，默认关闭）");
        cbCta.setTextSize(14);
        root.addView(cbCta);

        // ⑤ Help
        root.addView(sectionTitle("⑤ 使用步骤与限制"));
        TextView help = new TextView(this);
        help.setTextSize(12);
        help.setText(HELP_TEXT);
        root.addView(help);

        return scroll;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(18), 0, dp(4));
        return tv;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void onCaptureClick() {
        String cookie = captureCookie();
        if (cookie.isEmpty()) {
            setStatus("未从 WebView 读取到 Cookie。请先在上方网页中完成小米账号登录，再点「读取登录Cookie」。\n"
                    + "如果网页登录不便，可直接粘贴 Cookie，或使用第③节「系统账号捕获的 Cookie」。");
        } else {
            cookieEdit.setText(cookie);
            setStatus("已读取 Cookie（" + cookie.length() + " 字符）。请检查内容后点「保存设置」。");
        }
    }

    private void onSaveClick() {
        String cookie = cookieEdit.getText().toString().trim();
        SharedPreferences p = ModuleConfig.prefs(this);
        boolean ok = p.edit()
                .putString(ModuleConfig.KEY_COOKIE, cookie)
                .putBoolean(ModuleConfig.KEY_ENABLE_GATE_BYPASS, cbGate.isChecked())
                .putBoolean(ModuleConfig.KEY_ENABLE_COOKIE, cbCookie.isChecked())
                .putBoolean(ModuleConfig.KEY_FORCE_CTA, cbCta.isChecked())
                .commit();
        String msg = "已保存" + (ok ? "成功" : "失败") + "。Cookie " + cookie.length() + " 字符；"
                + "门禁绕过=" + (cbGate.isChecked() ? "开" : "关") + "，"
                + "Cookie注入=" + (cbCookie.isChecked() ? "开" : "关") + "，"
                + "强制CTA=" + (cbCta.isChecked() ? "开" : "关") + "。\n"
                + "请强制停止 com.miui.mediaeditor 与 com.miui.gallery 后重新打开（无需重启手机）。";
        setStatus(msg);
        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
    }

    private void onClearClick() {
        cookieEdit.setText("");
        ModuleConfig.prefs(this).edit().remove(ModuleConfig.KEY_COOKIE).apply();
        setStatus("已清空手动 Cookie（第③节系统捕获的 Cookie 保留，可点上方按钮填入）。");
    }

    /**
     * Invokes the system Xiaomi authenticator login UI
     * (AccountManager.addAccount("com.xiaomi", "passportapi", ...)), which is
     * the same flow the mediaeditor app itself uses. After a successful login
     * the user opens 相册→编辑 once so the hooked app fetches the STS cookie,
     * which the module captures and shows in section (3).
     */
    private void onSystemAccountLoginClick() {
        try {
            AccountManager am = AccountManager.get(this);
            setStatus("正在调起系统「小米账号」登录界面…（部分 ROM 没有该界面，可改用上方网页登录或手动粘贴 Cookie）");
            am.addAccount("com.xiaomi", "passportapi", null, null, this,
                    new AccountManagerCallback<Bundle>() {
                        @Override
                        public void run(AccountManagerFuture<Bundle> future) {
                            String msg;
                            try {
                                Bundle result = future.getResult();
                                if (result != null && result.containsKey(AccountManager.KEY_ACCOUNT_NAME)) {
                                    msg = "系统小米账号登录成功：" + result.getString(AccountManager.KEY_ACCOUNT_NAME)
                                            + "\n请打开相册→编辑一次并触发云端/徕卡功能，"
                                            + "模块会自动捕获 App 取到的 STS Cookie；"
                                            + "再回本页点「把捕获的 Cookie 填入上方输入框」→「保存设置」。";
                                } else {
                                    msg = "系统账号登录流程已返回（可能被取消）。"
                                            + "若未登录成功，可改用上方网页登录或手动粘贴 Cookie。";
                                }
                            } catch (Throwable t) {
                                msg = "系统账号登录结果读取失败：" + t
                                        + "\n可改用上方网页登录或手动粘贴 Cookie。";
                            }
                            final String finalMsg = msg;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    setStatus(finalMsg);
                                    refreshCaptured();
                                }
                            });
                        }
                    }, null);
        } catch (Throwable t) {
            Log.e(TAG, "addAccount failed", t);
            setStatus("无法调起系统小米账号登录：" + t
                    + "\n请改用上方网页登录，或手动粘贴 Cookie。");
        }
    }

    private void onUseCapturedClick() {
        String captured = ModuleConfig.prefs(this)
                .getString(ModuleConfig.KEY_CAPTURED_COOKIE, "");
        if (captured == null || captured.isEmpty()) {
            setStatus("还没有捕获到 Cookie。请先在 LSPosed 中启用模块并勾选作用域 com.miui.mediaeditor，"
                    + "然后打开相册→编辑并触发一次云端/徕卡功能，模块会自动把 App 自己获取的 Cookie 保存到这里，"
                    + "之后再回到本页面点此按钮刷新。");
        } else {
            cookieEdit.setText(captured);
            setStatus("已填入系统账号捕获的 Cookie（" + captured.length()
                    + " 字符），请点「保存设置」生效。");
        }
    }

    private void loadConfig() {
        SharedPreferences p = ModuleConfig.prefs(this);
        cookieEdit.setText(p.getString(ModuleConfig.KEY_COOKIE, ""));
        cbGate.setChecked(p.getBoolean(ModuleConfig.KEY_ENABLE_GATE_BYPASS,
                ModuleConfig.DEFAULT_ENABLE_GATE_BYPASS));
        cbCookie.setChecked(p.getBoolean(ModuleConfig.KEY_ENABLE_COOKIE,
                ModuleConfig.DEFAULT_ENABLE_COOKIE));
        cbCta.setChecked(p.getBoolean(ModuleConfig.KEY_FORCE_CTA,
                ModuleConfig.DEFAULT_FORCE_CTA));
        refreshCaptured();
    }

    private void refreshCaptured() {
        if (capturedView == null) {
            return;
        }
        String captured = "";
        try {
            captured = ModuleConfig.prefs(this).getString(ModuleConfig.KEY_CAPTURED_COOKIE, "");
        } catch (Throwable ignored) {
        }
        if (captured == null || captured.isEmpty()) {
            capturedView.setText("尚未捕获。需先在系统设置里登录小米账号 → 打开相册编辑并触发一次云端请求；"
                    + "LSPosed 挂钩进程会把 App 自己取到的 Cookie 自动保存到这里。");
        } else {
            capturedView.setText("已捕获（" + captured.length() + " 字符）：\n" + captured);
        }
    }

    private void setStatus(String text) {
        if (statusView != null) {
            statusView.setText(text);
        }
        Log.i(TAG, "status: " + text);
    }

    /**
     * Reads cookies for the known Xiaomi domains and merges them into one
     * Cookie header (dedupe by cookie name, first hit wins).
     */
    private String captureCookie() {
        LinkedHashMap<String, String> merged = new LinkedHashMap<String, String>();
        CookieManager cm = CookieManager.getInstance();
        for (int i = 0; i < COOKIE_URLS.length; i++) {
            String raw = null;
            try {
                raw = cm.getCookie(COOKIE_URLS[i]);
            } catch (Throwable t) {
                Log.w(TAG, "getCookie failed for " + COOKIE_URLS[i] + ": " + t);
            }
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String[] parts = raw.split(";");
            for (int j = 0; j < parts.length; j++) {
                String part = parts[j].trim();
                if (part.isEmpty()) {
                    continue;
                }
                int eq = part.indexOf('=');
                if (eq <= 0 || eq >= part.length() - 1) {
                    continue;
                }
                String name = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                if (name.isEmpty() || value.isEmpty()) {
                    continue;
                }
                if (!merged.containsKey(name)) {
                    merged.put(name, value);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        refreshCaptured();
    }

    @Override
    protected void onPause() {
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            if (webView != null) {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.destroy();
                webView = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private static String buildHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("【使用步骤】\n");
        sb.append("1. 安装本 APK 后打开 LSPosed 管理器 →「模块」→ 启用「徕卡一瞬 LSPosed」。\n");
        sb.append("2. 在模块作用域（勾选应用）中勾选：com.miui.mediaeditor（云端二次增强，必选）；"
                + "如使用相机「徕卡一瞬」，再勾选 com.android.camera；com.miui.gallery 可选。"
                + "保存后建议重启手机一次。\n");
        sb.append("2.1 首次启用后建议清除相册能力缓存一次：设置→应用→相册→存储→"
                + "清除缓存/清除数据（或 adb shell pm clear com.miui.gallery；会清除相册本地设置/缓存）。"
                + "相册是 Flutter+Rust 应用，Rust 层会缓存“能力不可用”的旧结果。\n");
        sb.append("3. 任选一种 Cookie 来源：\n");
        sb.append("   a) 本页网页登录小米账号 → 点「读取登录Cookie」→ 点「保存设置」；\n");
        sb.append("   b) 手动粘贴 Cookie → 点「保存设置」；\n");
        sb.append("   c) 系统账号：点第③节「登录系统小米账号」完成系统小米账号登录"
                + "（或已在系统设置登录），然后打开相册→编辑触发一次云端请求，"
                + "模块会自动捕获 App 自己取到的 STS Cookie，再回本页填入并保存。\n");
        sb.append("4. 【相机 / 徕卡一瞬 拍摄】需要 Magisk「CameraOnly」变体（v1.2.0，仅只读挂载国行 "
                + "MiuiCamera.apk，不修改任何系统属性）配合本模块：在 LSPosed 作用域勾选 "
                + "com.android.camera，重启相机进程后打开相机，模式栏应出现「徕卡一瞬」（模式 id 0x100）。"
                + "本模块只 hook 相机自身的 Java 判定方法，不读取/不修改属性。\n");
        sb.append("5. 修改 Cookie 或任意设置后：系统设置 → 应用 →「相册」/「媒体编辑器」/「相机」→ 强制停止，"
                + "再重新打开（不强制停止会继续使用进程内旧 Cookie/旧状态）。\n\n");
        sb.append("【说明与限制】\n");
        sb.append("· 本模块「无属性伪装」模式：不修改、不伪装任何系统属性"
                + "（不做属性读写，也不 hook SystemProperties）。门禁绕过改为直接 hook App 自身的 Java 判定方法返回 true："
                + "媒体编辑器 CN 为 rt.a.g()/ah0.b.g()、GLOBAL 为 mx.a.g()/r21.e.b()；"
                + "相机为 LegendaryEnter.support() 与 Te.c.W()（另有 h5() 兜底）。\n");
        sb.append("· Cookie 注入会把 App 从系统账号获取的 STS Cookie 替换为上方配置的 Cookie；"
                + "如果云端返回 401 或任务失败，可清空手动 Cookie，让模块回退使用系统账号 Cookie。\n");
        sb.append("· WebView 登录得到的 Cookie 可能与云 API 期望的 STS Cookie 不完全一致，"
                + "第③节「系统账号捕获的 Cookie」通常更可靠。\n");
        sb.append("· 云端权益（点数/配额/设备白名单）由小米服务端校验，本模块无法授予；"
                + "硬件认证 token 依赖系统小米账号与设备本身。\n");
        sb.append("· CTA 隐私协议是 App 正常弹出的用户同意窗口，本模块有意不强制；"
                + "「二次增强」入口是否显示只取决于上述判定方法，不需要绕过 CTA。\n");
        sb.append("· 设备未登录系统小米账号时，模块会 hook 上传前的登录探针（AccountUtil），"
                + "用 Cookie 中的 userId/cUserId 作为账号标识，避免上传直接失败 “not login”。\n");
        sb.append("· HyperOS 4 含大量 Rust 组件（相册为 Flutter+Rust，libapp_gallery.so 等），"
                + "LSPosed 无法 hook Dart/Rust 代码；但本路径的判定点在 mediaeditor 的 Java 代码"
                + "（provider 方法），因此不受影响。相册可能缓存旧的能力结果，"
                + "首次启用后按第 2.1 步清除相册缓存/数据一次即可。\n");
        sb.append("· 相机限制：厂商相机 HAL 的 legendary 能力（M9 CCD / M3 MONOPAN-50 / M10R 等）"
                + "与运行时 legend_mode SDK 不由 APK 提供，Java hook 只能让入口/UI 出现，"
                + "若 HAL 不支持则实际徕卡效果可能缺失或回退；国行 MiuiCamera APK 必需，"
                + "国际版相机 6.6 的等价 hook 未覆盖。云端权益仍由服务端校验。\n");
        sb.append("· 本项目为离线逆向研究用途，未在真机上测试，请自行承担风险。\n");
        sb.append("· 本模块不收集、不上传任何数据；Cookie 仅保存在本机模块私有目录。\n");
        return sb.toString();
    }
}
