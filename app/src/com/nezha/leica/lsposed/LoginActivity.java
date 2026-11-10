package com.nezha.leica.lsposed;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v2.3.0 full-screen Xiaomi login / STS helper.
 *
 * <p>The previous inline mini-WebView was not usable on a phone screen. This
 * activity provides a full-screen WebView with a slim top bar (返回 / 刷新 / 完成),
 * a progress bar, a friendly error overlay and a one-shot auto-recovery when the
 * {@code i.mi.com/sts} callback page errors out (stale sign/login state).</p>
 *
 * <p>Default URL is {@code https://i.mi.com/}: the i.mi.com web front-end starts
 * the real passport login -&gt; STS redirect chain with a valid sign. The
 * handcrafted {@code account.xiaomi.com/pass/serviceLogin?...sign=x} URL from an
 * earlier build must NOT be reintroduced (invalid sign -&gt; HTTP error page).</p>
 *
 * <p>On "完成" it merges cookies for account.xiaomi.com / i.mi.com /
 * gallery.i.mi.com / api.open.ai.xiaomi.com / xiaomi.com (dedupe by name, first
 * wins), returns them via {@link #EXTRA_COOKIE} and finishes. Token values are
 * never logged.</p>
 */
public class LoginActivity extends Activity {

    public static final String EXTRA_COOKIE = "cookie";

    private static final String TAG = "LeicaMomentLSP";
    private static final String START_URL = "https://i.mi.com/";

    /** Chrome-mobile UA: some passport/web cookies are bound to a browser UA. */
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 15; 2312DRA50C "
            + "Build/AP3A.240905.015) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final String[] COOKIE_URLS = new String[]{
            "https://account.xiaomi.com",
            "https://i.mi.com",
            "https://gallery.i.mi.com",
            "https://api.open.ai.xiaomi.com",
            "https://xiaomi.com"
    };

    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_BAR = 0xFF1B2228;
    private static final int COLOR_TEXT = 0xFFE8EDF2;
    private static final int COLOR_DIM = 0xFF9AA7B2;
    private static final int COLOR_ACCENT = 0xFF3D8BFD;
    private static final int COLOR_WARN = 0xFFFFB300;

    private WebView webView;
    private ProgressBar progress;
    private TextView statusView;
    private TextView errorView;
    private boolean stsRetried;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {
        }
        try {
            setContentView(buildUi());
            loadStartUrl();
        } catch (Throwable t) {
            Log.e(TAG, "LoginActivity onCreate failed", t);
            Toast.makeText(this, "\u767b\u5f55\u9875\u521d\u59cb\u5316\u5931\u8d25\uff1a" + t,
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        if (webView != null) {
            try {
                webView.onPause();
            } catch (Throwable ignored) {
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            try {
                webView.onResume();
            } catch (Throwable ignored) {
            }
        }
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

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable roundBg(int fill, int radiusPx, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (stroke != 0) {
            d.setStroke(dp(1), stroke);
        }
        return d;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // Slim top bar.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(COLOR_BAR);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));

        Button back = barButton("\u8fd4\u56de", "\u8fd4\u56de\u4e0a\u4e00\u9875");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55 / STS");
        title.setTextSize(15);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setContentDescription("\u767b\u5f55\u9875\u6807\u9898");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, tlp);

        Button refresh = barButton("\u5237\u65b0", "\u5237\u65b0\u5f53\u524d\u9875\u9762");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView != null) {
                    stsRetried = false;
                    webView.reload();
                }
            }
        });
        bar.addView(refresh);

        Button done = barButton("\u5b8c\u6210", "\u5b8c\u6210\u5e76\u8bfb\u53d6 Cookie");
        done.setBackground(roundBg(COLOR_ACCENT, dp(10), COLOR_ACCENT));
        done.setTextColor(Color.WHITE);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithCookie();
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(8);
        bar.addView(done, dlp);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // WebView + error overlay.
        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(content, clp);

        try {
            WebView wv = new WebView(this);
            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);
            ws.setSupportZoom(true);
            ws.setBuiltInZoomControls(true);
            ws.setDisplayZoomControls(false);
            ws.setUserAgentString(MOBILE_UA);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(wv, true);
            wv.setBackgroundColor(Color.WHITE);
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(0);
                    hideError();
                    statusView.setText("\u6b63\u5728\u52a0\u8f7d\uff1a" + url);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    progress.setVisibility(View.GONE);
                    statusView.setText("\u5df2\u52a0\u8f7d\u3002\u5b8c\u6210\u767b\u5f55/STS \u540e\u70b9\u53f3\u4e0a\u89d2\u300c\u5b8c\u6210\u300d\u8bfb\u53d6 Cookie\u3002");
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                WebResourceResponse errorResponse) {
                    if (request == null || !request.isForMainFrame()) {
                        return;
                    }
                    String url = request.getUrl() == null ? "" : request.getUrl().toString();
                    int code = errorResponse == null ? -1 : errorResponse.getStatusCode();
                    if (!stsRetried && url != null && url.contains("i.mi.com/sts")) {
                        stsRetried = true;
                        statusView.setText("STS \u56de\u8c03\u9875\u8fd4\u56de HTTP " + code
                                + "\uff08\u5e38\u89c1\u4e8e\u65e7 sign/\u767b\u5f55\u6001\u5931\u6548\uff09\uff0c"
                                + "\u5df2\u81ea\u52a8\u56de\u5230 i.mi.com \u91cd\u65b0\u8d70\u767b\u5f55\u6d41\u7a0b\u2026");
                        try {
                            view.loadUrl(START_URL);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    showError("\u9875\u9762\u8fd4\u56de HTTP " + code + "\uff1a" + url
                            + "\n\u53ef\u70b9\u4e0b\u65b9\u91cd\u8bd5\uff0c\u6216\u8fd4\u56de\u4f7f\u7528\u300c\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u300d"
                            + "\u7684 STS Cookie\u3002");
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                                            WebResourceError error) {
                    if (request == null || !request.isForMainFrame()) {
                        return;
                    }
                    String url = request.getUrl() == null ? "" : request.getUrl().toString();
                    if (!stsRetried && url != null && url.contains("i.mi.com/sts")) {
                        stsRetried = true;
                        statusView.setText("STS \u56de\u8c03\u9875\u52a0\u8f7d\u5931\u8d25\uff0c"
                                + "\u5df2\u81ea\u52a8\u56de\u5230 i.mi.com \u91cd\u65b0\u8d70\u767b\u5f55\u6d41\u7a0b\u2026");
                        try {
                            view.loadUrl(START_URL);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    int code = error == null ? -1 : error.getErrorCode();
                    String desc = error == null || error.getDescription() == null
                            ? "" : String.valueOf(error.getDescription());
                    showError("\u9875\u9762\u52a0\u8f7d\u5931\u8d25\uff08" + code + " " + desc + "\uff09\uff1a" + url
                            + "\n\u8bf7\u68c0\u67e5\u7f51\u7edc\uff1b\u82e5\u4ecd\u5931\u8d25\uff0c\u8bf7\u6539\u7528\u300c\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u300d\u7684 Cookie\u3002");
                }
            });
            wv.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    progress.setProgress(newProgress);
                }
            });
            webView = wv;
            content.addView(wv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            Log.e(TAG, "LoginActivity webview unavailable", t);
            showError("\u7cfb\u7edf WebView \u4e0d\u53ef\u7528\uff1a\u8bf7\u5148\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d\u542f\u7528 "
                    + "Android System WebView\uff0c\u7136\u540e\u91cd\u8bd5\u3002\u4e5f\u53ef\u8fd4\u56de\u6a21\u5757\u624b\u52a8\u7c98\u8d34 Cookie\u3002");
        }

        errorView = new TextView(this);
        errorView.setTextSize(13);
        errorView.setTextColor(COLOR_WARN);
        errorView.setBackgroundColor(0xE61B2228);
        errorView.setPadding(dp(16), dp(16), dp(16), dp(16));
        errorView.setGravity(Gravity.CENTER);
        errorView.setTextIsSelectable(true);
        errorView.setVisibility(View.GONE);
        errorView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                if (webView != null) {
                    stsRetried = false;
                    webView.loadUrl(START_URL);
                }
            }
        });
        content.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        statusView = new TextView(this);
        statusView.setTextSize(11);
        statusView.setTextColor(COLOR_DIM);
        statusView.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusView.setBackgroundColor(COLOR_BAR);
        statusView.setTextIsSelectable(true);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private Button barButton(String label, String desc) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(COLOR_TEXT);
        b.setContentDescription(desc);
        b.setBackground(roundBg(COLOR_BAR, dp(10), 0xFF2C363F));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        return b;
    }

    private void showError(String message) {
        if (errorView != null) {
            errorView.setText(message + "\n\n\uff08\u70b9\u6b64\u91cd\u8bd5\uff09");
            errorView.setVisibility(View.VISIBLE);
        }
    }

    private void hideError() {
        if (errorView != null) {
            errorView.setVisibility(View.GONE);
        }
    }

    private void loadStartUrl() {
        if (webView == null) {
            return;
        }
        try {
            webView.loadUrl(START_URL);
            statusView.setText("\u6b63\u5728\u6253\u5f00 i.mi.com\u2026\u82e5\u672a\u767b\u5f55\u4f1a\u81ea\u52a8\u8df3\u8f6c"
                    + "\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55\uff0c\u767b\u5f55\u540e\u81ea\u52a8\u5b8c\u6210 STS\u3002");
        } catch (Throwable t) {
            showError("\u6253\u5f00 i.mi.com \u5931\u8d25\uff1a" + t);
        }
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    private void finishWithCookie() {
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        String cookie = mergeCookies();
        if (cookie.isEmpty()) {
            showError("\u672a\u8bfb\u53d6\u5230 Cookie\u3002\u8bf7\u786e\u8ba4\u5df2\u5728\u9875\u9762\u4e2d\u5b8c\u6210\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55"
                    + "\uff08\u53ef\u5148\u5237\u65b0\u9875\u9762\u540e\u91cd\u8bd5\uff09\uff1b\u4e5f\u53ef\u8fd4\u56de\u6a21\u5757\u4f7f\u7528\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u7684 Cookie\u3002");
            return;
        }
        Intent data = new Intent();
        data.putExtra(EXTRA_COOKIE, cookie);
        setResult(RESULT_OK, data);
        Log.i(TAG, "LoginActivity returned cookie length=" + cookie.length()); // never log values
        finish();
    }

    private String mergeCookies() {
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
}
