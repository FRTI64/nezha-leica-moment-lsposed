package com.nezha.leica.lsposed;

import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Module UI v2.2.0: status overview, Xiaomi account / Cookie management
 * (WebView login + explicit STS step + manual paste + system-account capture),
 * Cookie composition inspector, read-only Cookie health probe, toggles and help.
 *
 * <p>Fully programmatic (no XML layouts). All network I/O runs on background
 * threads; token values are never logged and never shown in probe results
 * (only key names / lengths / HTTP codes / redacted snippets).</p>
 */
public class MainActivity extends Activity {

    private static final String TAG = "LeicaMomentLSP";

    /** Xiaomi passport login page (same one the CN ROM uses for the account flow). */
    private static final String LOGIN_URL =
            "https://account.xiaomi.com/pass/serviceLogin?sid=i.mi.com&callback="
                    + "https%3A%2F%2Fi.mi.com%2Fsts%3Fsign%3Dx%26followup%3Dhttps%253A%252F%252Fi.mi.com%252Fvip";

    /** Opening i.mi.com triggers Xiaomi STS / serviceToken issuance for .mi.com/.xiaomi.com. */
    private static final String STS_URL = "https://i.mi.com/";

    /** Generic gallery UA; the cloud API uses MiuiGallery/<versionCode>. */
    private static final String PROBE_UA = "MiuiGallery/210994048";

    /** Cookie sources merged by "读取登录 Cookie" (dedupe by name, first wins). */
    private static final String[] COOKIE_URLS = new String[]{
            "https://account.xiaomi.com",
            "https://i.mi.com",
            "https://gallery.i.mi.com",
            "https://api.open.ai.xiaomi.com",
            "https://xiaomi.com"
    };

    /** Read-only GET endpoints used by the Cookie health probe (never POST). */
    private static final String[] PROBE_ENDPOINTS = new String[]{
            "https://i.mi.com/status/lite/setting",
            "https://gallery.i.mi.com",
            "https://i.mi.com/gallery/public/resource/info"
    };

    private static final String[] KNOWN_COOKIE_KEYS = new String[]{
            "passToken", "serviceToken", "cUserId", "userId",
            "deviceId", "passInfo", "uLocale", "pass_ua"
    };

    private static final String[] LOGIN_MARKERS = new String[]{
            "serviceLogin", "account.xiaomi.com/pass", "passport", "\"login\"",
            "not login", "not_login", "\u8bf7\u767b\u5f55", "<title>\u767b\u5f55", "signin"
    };

    private static final int MAX_BODY = 8192;
    private static final int TIMEOUT_MS = 8000;

    private static final int VERDICT_OK = 0;
    private static final int VERDICT_WARN = 1;
    private static final int VERDICT_BAD = 2;

    private static final String HELP_TEXT = buildHelpText();

    // ------------------------------------------------------------------
    // Palette (dark-mode aware)
    // ------------------------------------------------------------------
    private boolean dark;
    private int colorBg;
    private int colorCard;
    private int colorBorder;
    private int colorText;
    private int colorTextDim;
    private int colorAccent;
    private int colorGreen;
    private int colorYellow;
    private int colorRed;
    private int colorFieldBg;

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------
    private LinearLayout root;
    private TextView statusPill;
    private TextView gateRow;
    private TextView cookieRow;
    private TextView stsRow;
    private TextView probeRow;
    private LinearLayout webContainer;
    private WebView webView;
    private EditText cookieEdit;
    private LinearLayout inspectorChips;
    private TextView inspectorSummary;
    private TextView stsWarning;
    private TextView capturedView;
    private TextView actionStatus;
    private Button probeButton;
    private TextView probeStatus;
    private LinearLayout probeResults;
    private TextView probeAggregate;
    private CheckBox cbGate;
    private CheckBox cbCookie;
    private TextView helpView;
    private Button helpToggle;

    private volatile boolean probing;
    private final List<ProbeResult> lastProbe = new ArrayList<ProbeResult>();

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            initPalette();
            setContentView(buildUi());
            loadConfig();
            updateOverview();
            updateInspector();
            updateStatusPill();
            updateProbeUiState();
            refreshCaptured();
        } catch (Throwable t) {
            Log.e(TAG, "onCreate failed", t);
            setActionStatus("\u754c\u9762\u521d\u59cb\u5316\u5931\u8d25\uff1a" + t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            refreshCaptured();
        } catch (Throwable ignored) {
        }
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

    // ------------------------------------------------------------------
    // Palette / drawable helpers
    // ------------------------------------------------------------------

    private void initPalette() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = mode == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            colorBg = 0xFF0F1418;
            colorCard = 0xFF1B2228;
            colorBorder = 0xFF2C363F;
            colorText = 0xFFE8EDF2;
            colorTextDim = 0xFF9AA7B2;
            colorAccent = 0xFF3D8BFD;
            colorGreen = 0xFF4CAF50;
            colorYellow = 0xFFFFB300;
            colorRed = 0xFFEF5350;
            colorFieldBg = 0xFF11181D;
        } else {
            colorBg = 0xFFF2F4F7;
            colorCard = 0xFFFFFFFF;
            colorBorder = 0xFFE1E5EA;
            colorText = 0xFF1B1F23;
            colorTextDim = 0xFF66707A;
            colorAccent = 0xFF1A73E8;
            colorGreen = 0xFF2E7D32;
            colorYellow = 0xFFB26A00;
            colorRed = 0xFFC62828;
            colorFieldBg = 0xFFF6F8FA;
        }
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int colorForLevel(int level) {
        if (level == VERDICT_OK) {
            return colorGreen;
        }
        if (level == VERDICT_WARN) {
            return colorYellow;
        }
        return colorRed;
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        buildHeader();
        buildStatusCard();
        buildCookieCard();
        buildProbeCard();
        buildTogglesCard();
        buildHelpCard();
        buildFooter();
        return scroll;
    }

    private void buildHeader() {
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("\u5f95\u5361\u4e00\u77ac LSPosed", 22, colorText, true);
        title.setContentDescription("\u5f95\u5361\u4e00\u77ac LSPosed \u6a21\u5757");
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ver = text("v" + ModuleConfig.MODULE_VERSION, 12, colorAccent, true);
        ver.setPadding(dp(10), dp(3), dp(10), dp(3));
        ver.setBackground(roundBg(colorFieldBg, dp(10), colorBorder));
        titleRow.addView(ver);
        root.addView(titleRow);

        TextView sub = text("LSPosed \u6a21\u5757 \u00b7 \u76f4\u63a5\u65b9\u6cd5 Hook \u00b7 \u96f6\u5c5e\u6027\u4f2a\u88c5",
                12, colorTextDim, false);
        sub.setPadding(0, dp(4), 0, 0);
        root.addView(sub);

        statusPill = text("\u72b6\u6001\u52a0\u8f7d\u4e2d\u2026", 13, Color.WHITE, true);
        statusPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusPill.setBackground(roundBg(colorAccent, dp(16), 0));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(10);
        plp.bottomMargin = dp(14);
        root.addView(statusPill, plp);
    }

    private void buildStatusCard() {
        LinearLayout body = sectionCard("\u2460 \u72b6\u6001\u603b\u89c8");
        gateRow = infoRow(body, "\u95e8\u7981\u89e3\u9501 Hook");
        cookieRow = infoRow(body, "Cookie \u914d\u7f6e");
        stsRow = infoRow(body, "STS / serviceToken");
        probeRow = infoRow(body, "Cookie \u4f53\u68c0");
    }

    private void buildCookieCard() {
        LinearLayout body = sectionCard("\u2461 \u8d26\u53f7\u4e0e Cookie");

        TextView hint = text("WebView \u767b\u5f55\u7528\u4e8e\u6293 Cookie\uff1b\u82e5\u4e91 API \u9700\u8981 STS\uff0c"
                + "\u8bf7\u518d\u6253\u5f00 i.mi.com \u5b8c\u6210 STS \u540e\u91cd\u65b0\u8bfb\u53d6\u3002", 12, colorTextDim, false);
        hint.setPadding(0, 0, 0, dp(4));
        body.addView(hint);

        LinearLayout row1 = buttonRow(body);
        addRowButton(row1, "\u6253\u5f00\u767b\u5f55\u9875", "\u6253\u5f00\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55\u9875", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onLoginClick();
                    }
                });
        addRowButton(row1, "\u53bb i.mi.com \u5b8c\u6210 STS", "\u6253\u5f00 i.mi.com \u4ee5\u83b7\u53d6 serviceToken",
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onStsClick();
                    }
                });

        LinearLayout row2 = buttonRow(body);
        addRowButton(row2, "\u8bfb\u53d6\u767b\u5f55 Cookie", "\u4ece WebView \u5408\u5e76\u8bfb\u53d6 Cookie", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onCaptureClick();
                    }
                });
        addRowButton(row2, "\u590d\u5236 Cookie", "\u590d\u5236\u5f53\u524d Cookie \u5230\u526a\u8d34\u677f", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onCopyClick();
                    }
                });

        LinearLayout row3 = buttonRow(body);
        addRowButton(row3, "\u4fdd\u5b58\u8bbe\u7f6e", "\u4fdd\u5b58 Cookie \u4e0e\u5f00\u5173", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onSaveClick();
                    }
                });
        addRowButton(row3, "\u6e05\u7a7a Cookie", "\u6e05\u7a7a\u624b\u52a8 Cookie", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onClearClick();
                    }
                });

        // Collapsible WebView container.
        webContainer = new LinearLayout(this);
        webContainer.setOrientation(LinearLayout.VERTICAL);
        webContainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams wclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wclp.topMargin = dp(10);
        body.addView(webContainer, wclp);
        try {
            WebView wv = new WebView(this);
            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);
            ws.setUserAgentString(ws.getUserAgentString()
                    + " LeicaMomentLSP/" + ModuleConfig.MODULE_VERSION);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(wv, true);
            wv.setBackgroundColor(colorFieldBg);
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    setActionStatus("\u7f51\u9875\u5df2\u52a0\u8f7d\uff1a" + url
                            + "\n\u767b\u5f55\u5b8c\u6210\u540e\u8bf7\u70b9\u300c\u8bfb\u53d6\u767b\u5f55 Cookie\u300d\uff1b"
                            + "STS \u9700\u6253\u5f00 i.mi.com \u5b8c\u6210\u540e\u518d\u8bfb\u53d6\u3002");
                }
            });
            webView = wv;
            webContainer.addView(wv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));
        } catch (Throwable t) {
            Log.e(TAG, "webview unavailable", t);
            TextView fail = text("\u7cfb\u7edf WebView \u4e0d\u53ef\u7528\uff1a\u8bf7\u5148\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d"
                    + "\u542f\u7528 Android System WebView\uff1b\u4e5f\u53ef\u76f4\u63a5\u7c98\u8d34 Cookie\u3002",
                    12, colorYellow, false);
            fail.setPadding(0, dp(6), 0, dp(6));
            webContainer.addView(fail);
            webView = null;
        }

        TextView editLabel = text("Cookie \u5b57\u7b26\u4e32\uff08\u53ef\u7f16\u8f91 / \u7c98\u8d34\uff09", 13, colorText, true);
        editLabel.setPadding(0, dp(12), 0, dp(4));
        body.addView(editLabel);

        cookieEdit = new EditText(this);
        cookieEdit.setHint("passToken=\u2026; cUserId=\u2026; userId=\u2026; serviceToken=\u2026");
        cookieEdit.setTextSize(12);
        cookieEdit.setTypeface(Typeface.MONOSPACE);
        cookieEdit.setTextIsSelectable(true);
        cookieEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cookieEdit.setMinLines(5);
        cookieEdit.setMaxLines(10);
        cookieEdit.setGravity(Gravity.TOP | Gravity.START);
        cookieEdit.setPadding(dp(10), dp(10), dp(10), dp(10));
        cookieEdit.setBackground(roundBg(colorFieldBg, dp(10), colorBorder));
        cookieEdit.setTextColor(colorText);
        cookieEdit.setContentDescription("Cookie \u5b57\u7b26\u4e32\u8f93\u5165\u6846");
        cookieEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                try {
                    updateInspector();
                    updateOverview();
                    updateStatusPill();
                    updateProbeUiState();
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        body.addView(cookieEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView inspLabel = text("Cookie \u7ec4\u6210\u68c0\u67e5\uff08\u4ec5\u663e\u793a\u952e\u540d\uff0c\u4e0d\u663e\u793a\u4ee4\u724c\u503c\uff09",
                13, colorText, true);
        inspLabel.setPadding(0, dp(12), 0, dp(4));
        body.addView(inspLabel);

        inspectorChips = new LinearLayout(this);
        inspectorChips.setOrientation(LinearLayout.VERTICAL);
        body.addView(inspectorChips);

        inspectorSummary = text("", 12, colorTextDim, false);
        inspectorSummary.setPadding(0, dp(4), 0, 0);
        body.addView(inspectorSummary);

        stsWarning = text("", 12, colorYellow, true);
        stsWarning.setPadding(dp(8), dp(8), dp(8), dp(8));
        stsWarning.setVisibility(View.GONE);
        stsWarning.setBackground(roundBg(colorFieldBg, dp(8), colorYellow));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(8);
        body.addView(stsWarning, wlp);

        TextView capLabel = text("\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u7684 Cookie\uff08\u66f4\u53ef\u9760\uff09",
                13, colorText, true);
        capLabel.setPadding(0, dp(14), 0, dp(4));
        body.addView(capLabel);

        capturedView = text("\u5c1a\u672a\u6355\u83b7\u3002", 12, colorTextDim, false);
        capturedView.setTextIsSelectable(true);
        body.addView(capturedView);

        LinearLayout row4 = buttonRow(body);
        addRowButton(row4, "\u767b\u5f55\u7cfb\u7edf\u5c0f\u7c73\u8d26\u53f7", "\u8c03\u8d77\u7cfb\u7edf\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55",
                true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onSystemAccountLoginClick();
                    }
                });
        addRowButton(row4, "\u586b\u5165\u6355\u83b7\u7684 Cookie", "\u628a\u6355\u83b7\u7684 Cookie \u586b\u5165\u8f93\u5165\u6846",
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onUseCapturedClick();
                    }
                });

        actionStatus = text("", 12, colorTextDim, false);
        actionStatus.setTextIsSelectable(true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(10);
        body.addView(actionStatus, alp);
    }

    private void buildProbeCard() {
        LinearLayout body = sectionCard("\u2462 Cookie \u4f53\u68c0\uff08\u53ea\u8bfb\u63a2\u6d4b\uff09");

        TextView desc = text("\u4f7f\u7528\u5f53\u524d Cookie \u5bf9\u5c0f\u7c73\u4e91\u7aef\u53d1\u8d77 GET \u53ea\u8bfb\u63a2\u6d4b"
                + "\uff08\u4e0d\u6d88\u8017\u914d\u989d\uff09\u3002\u7ed3\u679c\u4ec5\u7528\u4e8e\u5224\u65ad Cookie \u662f\u5426\u88ab\u63a5\u53d7\uff1b"
                + "\u4e91\u7aef\u6743\u76ca\u4ecd\u7531\u5c0f\u7c73\u670d\u52a1\u7aef\u6821\u9a8c\u3002", 12, colorTextDim, false);
        body.addView(desc);

        probeButton = actionButton("\u68c0\u6d4b Cookie \u6709\u6548\u6027", "\u68c0\u6d4b Cookie \u6709\u6548\u6027",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onProbeClick();
                    }
                });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        body.addView(probeButton, blp);

        probeStatus = text("", 12, colorTextDim, false);
        probeStatus.setPadding(0, dp(6), 0, 0);
        body.addView(probeStatus);

        probeResults = new LinearLayout(this);
        probeResults.setOrientation(LinearLayout.VERTICAL);
        body.addView(probeResults);

        probeAggregate = text("", 12, colorText, false);
        probeAggregate.setTextIsSelectable(true);
        probeAggregate.setLineSpacing(0f, 1.1f);
        LinearLayout.LayoutParams aglp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aglp.topMargin = dp(8);
        body.addView(probeAggregate, aglp);

        TextView note = text("\u63a2\u6d4b\u7aef\u70b9\u4e3a\u53ea\u8bfb GET\uff0c\u53ef\u80fd\u968f\u670d\u52a1\u7aef\u8c03\u6574\uff1b"
                + "\u4e0d\u4f1a\u4e0a\u4f20/\u8bb0\u5f55 Cookie \u4e2d\u7684\u4ee4\u724c\u503c\u3002", 11, colorTextDim, false);
        note.setPadding(0, dp(6), 0, 0);
        body.addView(note);
    }

    private void buildTogglesCard() {
        LinearLayout body = sectionCard("\u2463 \u529f\u80fd\u5f00\u5173");

        cbGate = new CheckBox(this);
        cbGate.setText("\u542f\u7528\u95e8\u7981\u89e3\u9501\uff08\u76f4\u63a5 Hook \u5224\u5b9a\u65b9\u6cd5 \u2192 true\uff0c"
                + "\u4e0d\u4fee\u6539\u4efb\u4f55\u7cfb\u7edf\u5c5e\u6027\uff09");
        cbGate.setTextSize(13);
        cbGate.setTextColor(colorText);
        cbGate.setContentDescription("\u542f\u7528\u95e8\u7981\u89e3\u9501\u5f00\u5173");
        body.addView(cbGate);

        cbCookie = new CheckBox(this);
        cbCookie.setText("\u542f\u7528 Cookie \u6ce8\u5165\uff08\u66ff\u6362 App \u4ece\u7cfb\u7edf\u8d26\u53f7\u83b7\u53d6\u7684 STS Cookie\uff09");
        cbCookie.setTextSize(13);
        cbCookie.setTextColor(colorText);
        cbCookie.setContentDescription("\u542f\u7528 Cookie \u6ce8\u5165\u5f00\u5173");
        body.addView(cbCookie);

        CheckBox cbCta = new CheckBox(this);
        cbCta.setText("\u5f3a\u5236 CTA \u5df2\u540c\u610f\uff08\u672c\u7248\u672c\u4e0d\u63d0\u4f9b\uff1bCTA \u4e3a\u6b63\u5e38\u9690\u79c1\u5f39\u7a97\uff09");
        cbCta.setTextSize(13);
        cbCta.setTextColor(colorTextDim);
        cbCta.setEnabled(false);
        cbCta.setChecked(false);
        cbCta.setContentDescription("\u5f3a\u5236 CTA \u5f00\u5173\u4e0d\u53ef\u7528");
        body.addView(cbCta);

        TextView note = text("Hook \u751f\u6548\u8303\u56f4\uff1acom.miui.mediaeditor\uff08\u7f16\u8f91\u5668 predicate\uff09"
                + "\u4e0e com.android.camera\uff08\u76f8\u673a H1/H2/H3\uff09\u3002\u4fee\u6539\u5f00\u5173/\u4fdd\u5b58\u540e\u8bf7\u5f3a\u5236\u505c\u6b62\u5bf9\u5e94 App\u3002",
                12, colorTextDim, false);
        note.setPadding(0, dp(8), 0, 0);
        body.addView(note);
    }

    private void buildHelpCard() {
        LinearLayout body = sectionCard("\u2464 \u4f7f\u7528\u8bf4\u660e\u4e0e\u9650\u5236");
        helpToggle = actionButton("\u5c55\u5f00\u8bf4\u660e\u4e0e\u9650\u5236", "\u5c55\u5f00\u6216\u6536\u8d77\u8bf4\u660e",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onToggleHelpClick();
                    }
                });
        body.addView(helpToggle);

        helpView = text(HELP_TEXT, 12, colorText, false);
        helpView.setTextIsSelectable(true);
        helpView.setLineSpacing(0f, 1.15f);
        helpView.setVisibility(View.GONE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        body.addView(helpView, hlp);
    }

    private void buildFooter() {
        TextView footer = text("\u79bb\u7ebf\u9006\u5411\u7814\u7a76\u7528\u9014 \u00b7 \u672a\u5728\u771f\u673a\u6d4b\u8bd5 \u00b7 "
                + "\u6a21\u5757\u4e0d\u6536\u96c6/\u4e0d\u4e0a\u4f20\u4efb\u4f55\u6570\u636e\uff1bCookie \u4ec5\u4fdd\u5b58\u5728\u672c\u673a\u6a21\u5757\u79c1\u6709\u76ee\u5f55\u3002",
                11, colorTextDim, false);
        footer.setPadding(0, dp(4), 0, dp(8));
        root.addView(footer);
    }

    // ------------------------------------------------------------------
    // Small UI helpers
    // ------------------------------------------------------------------

    private LinearLayout sectionCard(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.setBackground(roundBg(colorCard, dp(14), colorBorder));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        root.addView(card, clp);

        TextView tv = text(title, 16, colorText, true);
        tv.setContentDescription(title);
        tv.setPadding(0, 0, 0, dp(6));
        card.addView(tv);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private TextView infoRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView l = text(label, 13, colorTextDim, false);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text("\u2014", 13, colorText, true);
        v.setGravity(Gravity.END);
        row.addView(v);
        parent.addView(row);
        return v;
    }

    private LinearLayout buttonRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(row, lp);
        return row;
    }

    private Button addRowButton(LinearLayout row, String label, String desc, boolean first,
                                View.OnClickListener listener) {
        Button b = actionButton(label, desc, listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (!first) {
            lp.leftMargin = dp(8);
        }
        row.addView(b, lp);
        return b;
    }

    private Button actionButton(String label, String desc, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setContentDescription(desc);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        b.setBackground(roundBg(colorAccent, dp(10), colorAccent));
        b.setOnClickListener(listener);
        return b;
    }

    private void addChipRow(LinearLayout parent, List<TextView> chips) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        parent.addView(row, rlp);
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.leftMargin = dp(6);
            }
            row.addView(chip, lp);
        }
    }

    private TextView chip(String label, int level) {
        TextView tv = text(label, 11, Color.WHITE, true);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        int color = colorForLevel(level);
        tv.setBackground(roundBg(color, dp(8), color));
        return tv;
    }

    // ------------------------------------------------------------------
    // Action handlers
    // ------------------------------------------------------------------

    private void onLoginClick() {
        setWebExpanded(true);
        if (webView == null) {
            setActionStatus("\u7cfb\u7edf WebView \u4e0d\u53ef\u7528\uff0c\u8bf7\u76f4\u63a5\u7c98\u8d34 Cookie\u3002");
            return;
        }
        try {
            webView.loadUrl(LOGIN_URL);
            setActionStatus("\u5df2\u6253\u5f00\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55\u9875\u3002\u767b\u5f55\u6210\u529f\u540e\u70b9\u300c\u8bfb\u53d6\u767b\u5f55 Cookie\u300d\u3002");
        } catch (Throwable t) {
            setActionStatus("\u6253\u5f00\u767b\u5f55\u9875\u5931\u8d25\uff1a" + t);
        }
    }

    private void onStsClick() {
        setWebExpanded(true);
        if (webView == null) {
            setActionStatus("\u7cfb\u7edf WebView \u4e0d\u53ef\u7528\uff0c\u8bf7\u76f4\u63a5\u7c98\u8d34 Cookie \u6216\u7528\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u3002");
            return;
        }
        try {
            webView.loadUrl(STS_URL);
            setActionStatus("\u5df2\u6253\u5f00 i.mi.com\uff1a\u767b\u5f55\u72b6\u6001\u4e0b\u8bbf\u95ee\u4f1a\u89e6\u53d1 STS"
                    + "\uff08serviceToken\uff09\u3002\u5b8c\u6210\u540e\u70b9\u300c\u8bfb\u53d6\u767b\u5f55 Cookie\u300d\u3002");
        } catch (Throwable t) {
            setActionStatus("\u6253\u5f00 i.mi.com \u5931\u8d25\uff1a" + t);
        }
    }

    private void setWebExpanded(boolean expanded) {
        if (webContainer != null) {
            webContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
    }

    private void onCaptureClick() {
        String cookie = captureCookie();
        if (cookie.isEmpty()) {
            setActionStatus("\u672a\u4ece WebView \u8bfb\u53d6\u5230 Cookie\u3002\u8bf7\u5148\u5728\u4e0a\u65b9\u767b\u5f55\u9875\u767b\u5f55"
                    + "\uff08\u6216\u6253\u5f00 i.mi.com \u5b8c\u6210 STS\uff09\u540e\u518d\u70b9\u8bfb\u53d6\u3002");
            return;
        }
        cookieEdit.setText(cookie);
        setActionStatus("\u5df2\u8bfb\u53d6 Cookie\uff08" + cookie.length()
                + " \u5b57\u7b26\uff09\u3002\u8bf7\u68c0\u67e5\u7ec4\u6210\u540e\u70b9\u300c\u4fdd\u5b58\u8bbe\u7f6e\u300d\u3002");
    }

    private void onCopyClick() {
        String cookie = currentCookie();
        if (cookie.isEmpty()) {
            setActionStatus("\u6ca1\u6709\u53ef\u590d\u5236\u7684 Cookie\u3002");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Xiaomi Cookie", cookie));
            setActionStatus("\u5df2\u590d\u5236\u5230\u526a\u8d34\u677f\uff08\u6ce8\u610f\uff1a\u526a\u8d34\u677f\u5185\u5bb9\u53ef\u80fd\u88ab\u5176\u5b83\u5e94\u7528\u8bfb\u53d6\uff09\u3002");
        } catch (Throwable t) {
            setActionStatus("\u590d\u5236\u5931\u8d25\uff1a" + t);
        }
    }

    private void onSaveClick() {
        String cookie = cookieEdit.getText().toString().trim();
        SharedPreferences p = ModuleConfig.prefs(this);
        boolean ok;
        try {
            ok = p.edit()
                    .putString(ModuleConfig.KEY_COOKIE, cookie)
                    .putBoolean(ModuleConfig.KEY_ENABLE_GATE_BYPASS, cbGate.isChecked())
                    .putBoolean(ModuleConfig.KEY_ENABLE_COOKIE, cbCookie.isChecked())
                    .putBoolean(ModuleConfig.KEY_FORCE_CTA, false)
                    .commit();
        } catch (Throwable t) {
            setActionStatus("\u4fdd\u5b58\u5931\u8d25\uff1a" + t);
            return;
        }
        updateOverview();
        updateInspector();
        updateStatusPill();
        updateProbeUiState();
        setActionStatus("\u5df2\u4fdd\u5b58" + (ok ? "\u6210\u529f" : "\u5931\u8d25") + "\u3002Cookie "
                + cookie.length() + " \u5b57\u7b26\uff1b\u95e8\u7981\u89e3\u9501="
                + (cbGate.isChecked() ? "\u5f00" : "\u5173") + "\uff0cCookie \u6ce8\u5165="
                + (cbCookie.isChecked() ? "\u5f00" : "\u5173") + "\u3002\n"
                + "\u8bf7\u5f3a\u5236\u505c\u6b62\u76f8\u5e94 App \u540e\u91cd\u65b0\u6253\u5f00\u3002");
        Toast.makeText(this, "\u4fdd\u5b58\u6210\u529f", Toast.LENGTH_SHORT).show();
    }

    private void onClearClick() {
        try {
            cookieEdit.setText("");
            ModuleConfig.prefs(this).edit().remove(ModuleConfig.KEY_COOKIE).apply();
            updateOverview();
            updateInspector();
            updateStatusPill();
            updateProbeUiState();
            setActionStatus("\u5df2\u6e05\u7a7a\u624b\u52a8 Cookie\uff08\u7cfb\u7edf\u6355\u83b7\u7684 Cookie \u4fdd\u7559\uff09\u3002");
        } catch (Throwable t) {
            setActionStatus("\u6e05\u7a7a\u5931\u8d25\uff1a" + t);
        }
    }

    private void onUseCapturedClick() {
        try {
            String captured = ModuleConfig.prefs(this)
                    .getString(ModuleConfig.KEY_CAPTURED_COOKIE, "");
            if (captured == null || captured.isEmpty()) {
                setActionStatus("\u8fd8\u6ca1\u6709\u6355\u83b7\u5230 Cookie\u3002\u8bf7\u5148\u5728 LSPosed \u4e2d\u542f\u7528\u6a21\u5757\u5e76\u52fe\u9009\u4f5c\u7528\u57df"
                        + " com.miui.mediaeditor\uff0c\u7136\u540e\u6253\u5f00\u76f8\u518c\u2192\u7f16\u8f91\u4e00\u6b21\u89e6\u53d1\u4e91\u7aef\u8bf7\u6c42\u3002");
                return;
            }
            cookieEdit.setText(captured);
            setActionStatus("\u5df2\u586b\u5165\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u7684 Cookie\uff08" + captured.length()
                    + " \u5b57\u7b26\uff09\uff0c\u8bf7\u70b9\u300c\u4fdd\u5b58\u8bbe\u7f6e\u300d\u3002");
        } catch (Throwable t) {
            setActionStatus("\u586b\u5165\u5931\u8d25\uff1a" + t);
        }
    }

    private void onToggleHelpClick() {
        if (helpView == null) {
            return;
        }
        boolean show = helpView.getVisibility() != View.VISIBLE;
        helpView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (helpToggle != null) {
            helpToggle.setText(show ? "\u6536\u8d77\u8bf4\u660e\u4e0e\u9650\u5236" : "\u5c55\u5f00\u8bf4\u660e\u4e0e\u9650\u5236");
        }
    }

    /**
     * Invokes the system Xiaomi authenticator login UI
     * (AccountManager.addAccount("com.xiaomi", "passportapi", ...)), the same flow
     * the mediaeditor app uses. After login open 相册→编辑 once so the hooked app
     * fetches the STS cookie, which the module captures.
     */
    private void onSystemAccountLoginClick() {
        try {
            AccountManager am = AccountManager.get(this);
            setActionStatus("\u6b63\u5728\u8c03\u8d77\u7cfb\u7edf\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55\u754c\u9762\u2026"
                    + "\uff08\u90e8\u5206 ROM \u6ca1\u6709\u8be5\u754c\u9762\uff0c\u53ef\u6539\u7528\u7f51\u9875\u767b\u5f55\u6216\u624b\u52a8\u7c98\u8d34\uff09");
            am.addAccount("com.xiaomi", "passportapi", null, null, this,
                    new AccountManagerCallback<Bundle>() {
                        @Override
                        public void run(AccountManagerFuture<Bundle> future) {
                            String msg;
                            try {
                                Bundle result = future.getResult();
                                if (result != null && result.containsKey(AccountManager.KEY_ACCOUNT_NAME)) {
                                    msg = "\u7cfb\u7edf\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55\u6210\u529f\uff1a"
                                            + result.getString(AccountManager.KEY_ACCOUNT_NAME)
                                            + "\n\u8bf7\u6253\u5f00\u76f8\u518c\u2192\u7f16\u8f91\u4e00\u6b21\u5e76\u89e6\u53d1\u4e91\u7aef\u529f\u80fd\uff0c"
                                            + "\u6a21\u5757\u4f1a\u81ea\u52a8\u6355\u83b7 App \u53d6\u5230\u7684 STS Cookie\uff1b"
                                            + "\u518d\u56de\u672c\u9875\u70b9\u300c\u586b\u5165\u6355\u83b7\u7684 Cookie\u300d\u3002";
                                } else {
                                    msg = "\u7cfb\u7edf\u8d26\u53f7\u767b\u5f55\u6d41\u7a0b\u5df2\u8fd4\u56de\uff08\u53ef\u80fd\u88ab\u53d6\u6d88\uff09\u3002"
                                            + "\u82e5\u672a\u767b\u5f55\u6210\u529f\uff0c\u53ef\u6539\u7528\u7f51\u9875\u767b\u5f55\u6216\u624b\u52a8\u7c98\u8d34\u3002";
                                }
                            } catch (Throwable t) {
                                msg = "\u7cfb\u7edf\u8d26\u53f7\u767b\u5f55\u7ed3\u679c\u8bfb\u53d6\u5931\u8d25\uff1a" + t;
                            }
                            final String finalMsg = msg;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    setActionStatus(finalMsg);
                                    refreshCaptured();
                                }
                            });
                        }
                    }, null);
        } catch (Throwable t) {
            Log.e(TAG, "addAccount failed", t);
            setActionStatus("\u65e0\u6cd5\u8c03\u8d77\u7cfb\u7edf\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55\uff1a" + t
                    + "\n\u8bf7\u6539\u7528\u7f51\u9875\u767b\u5f55\uff0c\u6216\u624b\u52a8\u7c98\u8d34 Cookie\u3002");
        }
    }

    // ------------------------------------------------------------------
    // Cookie health probe (read-only GET)
    // ------------------------------------------------------------------

    private void onProbeClick() {
        final String cookie = currentCookie();
        if (cookie.isEmpty()) {
            setActionStatus("\u8bf7\u5148\u914d\u7f6e Cookie \u518d\u68c0\u6d4b\u3002");
            return;
        }
        if (probing) {
            return;
        }
        probing = true;
        updateProbeUiState();
        updateOverview();
        if (probeStatus != null) {
            probeStatus.setText("\u68c0\u6d4b\u4e2d\u2026\uff08\u6700\u591a " + (TIMEOUT_MS / 1000) + " \u79d2/\u7aef\u70b9\uff09");
        }
        if (probeResults != null) {
            probeResults.removeAllViews();
        }
        if (probeAggregate != null) {
            probeAggregate.setText("");
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ProbeResult> results = new ArrayList<ProbeResult>();
                try {
                    for (int i = 0; i < PROBE_ENDPOINTS.length; i++) {
                        results.add(probeOne(PROBE_ENDPOINTS[i], cookie));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "probe loop error: " + t);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        probing = false;
                        lastProbe.clear();
                        lastProbe.addAll(results);
                        renderProbeResults(results);
                        updateOverview();
                        updateStatusPill();
                        updateProbeUiState();
                        if (probeStatus != null) {
                            probeStatus.setText("\u68c0\u6d4b\u5b8c\u6210\uff1a" + results.size()
                                    + " \u4e2a\u7aef\u70b9\uff08\u53ea\u8bfb GET\uff09");
                        }
                    }
                });
            }
        }, "cookie-health-probe").start();
    }

    private ProbeResult probeOne(String endpoint, String cookie) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("User-Agent", PROBE_UA);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            InputStream is = null;
            try {
                is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            } catch (Throwable ignored) {
            }
            String body = readLimited(is);
            Log.i(TAG, "probe " + endpoint + " -> HTTP " + code); // never log cookie/token values
            if (code >= 200 && code < 300) {
                String snippet = sanitizeSnippet(redactCookieValues(body, cookie));
                if (containsLoginMarker(body)) {
                    return new ProbeResult(endpoint, VERDICT_WARN, code,
                            "HTTP 2xx \u4f46\u54cd\u5e94\u542b\u767b\u5f55\u63d0\u793a\uff08\u53ef\u80fd\u672a\u5b8c\u6210 STS\uff09", snippet);
                }
                return new ProbeResult(endpoint, VERDICT_OK, code,
                        "\u672a\u88ab\u62d2\u7edd\uff08Cookie \u88ab\u63a5\u53d7\uff09", snippet);
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = location == null ? "" : location.toLowerCase(Locale.US);
                if (loc.contains("login") || loc.contains("passport")
                        || loc.contains("signin") || loc.contains("servicelogin")) {
                    return new ProbeResult(endpoint, VERDICT_BAD, code,
                            "\u672a\u767b\u5f55/\u51ed\u636e\u65e0\u6548\uff08\u91cd\u5b9a\u5411\u5230\u767b\u5f55\u9875\uff09",
                            sanitizeSnippet(location));
                }
                return new ProbeResult(endpoint, VERDICT_WARN, code,
                        "HTTP \u91cd\u5b9a\u5411\uff08\u975e\u767b\u5f55\uff09", sanitizeSnippet(location));
            }
            if (code == 401 || code == 403) {
                return new ProbeResult(endpoint, VERDICT_BAD, code,
                        "\u51ed\u636e\u88ab\u62d2\u7edd\uff08HTTP " + code + "\uff09",
                        sanitizeSnippet(redactCookieValues(body, cookie)));
            }
            return new ProbeResult(endpoint, VERDICT_WARN, code, "HTTP " + code,
                    sanitizeSnippet(redactCookieValues(body, cookie)));
        } catch (Throwable t) {
            Log.i(TAG, "probe " + endpoint + " -> network error " + t.getClass().getSimpleName());
            return new ProbeResult(endpoint, VERDICT_BAD, -1,
                    "\u7f51\u7edc\u4e0d\u53ef\u8fbe/\u8d85\u65f6\uff08" + t.getClass().getSimpleName() + "\uff09", "");
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void renderProbeResults(List<ProbeResult> results) {
        if (probeResults == null) {
            return;
        }
        probeResults.removeAllViews();
        for (int i = 0; i < results.size(); i++) {
            ProbeResult r = results.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(r.verdict);
            if (r.code > 0) {
                sb.append(" \u00b7 HTTP ").append(r.code);
            }
            sb.append('\n').append(r.url);
            if (r.snippet != null && !r.snippet.isEmpty()) {
                sb.append('\n').append(r.snippet);
            }
            TextView tv = text(sb.toString(), 12, colorForLevel(r.level), false);
            tv.setTextIsSelectable(true);
            tv.setLineSpacing(0f, 1.05f);
            tv.setPadding(dp(10), dp(8), dp(10), dp(8));
            tv.setBackground(roundBg(colorFieldBg, dp(8), colorBorder));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            probeResults.addView(tv, lp);
        }
        if (probeAggregate == null) {
            return;
        }
        int ok = 0;
        int warn = 0;
        int bad = 0;
        for (int i = 0; i < results.size(); i++) {
            int lv = results.get(i).level;
            if (lv == VERDICT_OK) {
                ok++;
            } else if (lv == VERDICT_WARN) {
                warn++;
            } else {
                bad++;
            }
        }
        StringBuilder agg = new StringBuilder();
        if (bad > 0) {
            agg.append("\u7ed3\u8bba\uff1a\u5b58\u5728\u88ab\u62d2\u7edd/\u91cd\u5b9a\u5411\u7684\u63a2\u6d4b\u3002\u5f53\u524d Cookie \u53ef\u80fd\u65e0\u6548"
                    + "\u6216\u7f3a\u5c11 STS\uff08serviceToken\uff09\u3002\u5efa\u8bae\uff1a\u5b8c\u6210 i.mi.com STS \u540e\u91cd\u65b0\u8bfb\u53d6\uff0c"
                    + "\u6216\u4f7f\u7528\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u7684 Cookie\u3002");
        } else if (warn > 0) {
            agg.append("\u7ed3\u8bba\uff1a\u90e8\u5206\u63a2\u6d4b\u7ed3\u679c\u4e0d\u786e\u5b9a\uff08\u53ef\u80fd\u672a\u5b8c\u6210 STS \u6216\u7aef\u70b9\u8981\u6c42"
                    + "\u4e0d\u540c\u51ed\u636e\uff09\uff0c\u5176\u4f59\u672a\u88ab\u62d2\u7edd\u3002");
        } else {
            agg.append("\u7ed3\u8bba\uff1a\u6240\u6709\u53ea\u8bfb\u63a2\u6d4b\u5747\u672a\u88ab\u62d2\u7edd \u2192 \u5f53\u524d Cookie \u5927\u6982\u7387\u6709\u6548\u3002");
        }
        agg.append("\n\u6ce8\u610f\uff1a\u672c\u68c0\u6d4b\u4ec5\u9a8c\u8bc1 Cookie \u662f\u5426\u88ab\u63a5\u53d7\uff1b\u4e91\u7aef\u6743\u76ca"
                + "\uff08\u70b9\u6570/\u8bbe\u5907\u767d\u540d\u5355/\u786c\u4ef6\u8ba4\u8bc1 token\uff09\u7531\u5c0f\u7c73\u670d\u52a1\u7aef\u6821\u9a8c\uff0c"
                + "\u65e0\u6cd5\u901a\u8fc7\u672c\u68c0\u6d4b\u9a8c\u8bc1\u3002\u63a2\u6d4b\u7aef\u70b9\u4e3a\u53ea\u8bfb GET\uff0c\u53ef\u80fd\u968f\u670d\u52a1\u7aef\u8c03\u6574\u3002");
        probeAggregate.setText(agg.toString());
    }

    private String readLimited(InputStream in) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            char[] buf = new char[1024];
            int n;
            while (sb.length() < MAX_BODY && (n = br.read(buf)) > 0) {
                int take = Math.min(n, MAX_BODY - sb.length());
                sb.append(buf, 0, take);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    private boolean containsLoginMarker(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.US);
        for (int i = 0; i < LOGIN_MARKERS.length; i++) {
            if (lower.contains(LOGIN_MARKERS[i].toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeSnippet(String raw) {
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
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        if (s.length() > 140) {
            s = s.substring(0, 140) + "\u2026";
        }
        return s;
    }

    /** Redacts any cookie token value that could appear in a server response body. */
    private String redactCookieValues(String text, String cookie) {
        if (text == null || text.isEmpty() || cookie == null || cookie.isEmpty()) {
            return text == null ? "" : text;
        }
        String out = text;
        String[] parts = cookie.split(";");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String v = parts[i].substring(eq + 1).trim();
            if (v.length() >= 6 && out.contains(v)) {
                out = out.replace(v, "***");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // State / config
    // ------------------------------------------------------------------

    private String currentCookie() {
        try {
            if (cookieEdit != null) {
                return cookieEdit.getText().toString().trim();
            }
            return ModuleConfig.prefs(this).getString(ModuleConfig.KEY_COOKIE, "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void loadConfig() {
        SharedPreferences p = ModuleConfig.prefs(this);
        if (cookieEdit != null) {
            cookieEdit.setText(p.getString(ModuleConfig.KEY_COOKIE, ""));
        }
        if (cbGate != null) {
            cbGate.setChecked(p.getBoolean(ModuleConfig.KEY_ENABLE_GATE_BYPASS,
                    ModuleConfig.DEFAULT_ENABLE_GATE_BYPASS));
            cbGate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateOverview();
                    updateStatusPill();
                }
            });
        }
        if (cbCookie != null) {
            cbCookie.setChecked(p.getBoolean(ModuleConfig.KEY_ENABLE_COOKIE,
                    ModuleConfig.DEFAULT_ENABLE_COOKIE));
            cbCookie.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateOverview();
                    updateStatusPill();
                }
            });
        }
        refreshCaptured();
    }

    private void updateOverview() {
        if (gateRow == null) {
            return;
        }
        boolean gate = cbGate != null && cbGate.isChecked();
        String cookie = currentCookie();
        boolean hasSts = hasCookieKey(cookie, "serviceToken");
        gateRow.setText(gate ? "\u5df2\u542f\u7528" : "\u5df2\u5173\u95ed");
        gateRow.setTextColor(gate ? colorGreen : colorRed);
        cookieRow.setText(cookie.isEmpty() ? "\u672a\u914d\u7f6e" : ("\u5df2\u914d\u7f6e\uff08" + cookie.length() + " \u5b57\u7b26\uff09"));
        cookieRow.setTextColor(cookie.isEmpty() ? colorYellow : colorGreen);
        if (cookie.isEmpty()) {
            stsRow.setText("\u2014");
            stsRow.setTextColor(colorTextDim);
        } else {
            stsRow.setText(hasSts ? "\u542b serviceToken" : "\u7f3a\u5c11 serviceToken\uff08Web Cookie\uff09");
            stsRow.setTextColor(hasSts ? colorGreen : colorYellow);
        }
        if (probing) {
            probeRow.setText("\u68c0\u6d4b\u4e2d\u2026");
            probeRow.setTextColor(colorAccent);
        } else if (lastProbe.isEmpty()) {
            probeRow.setText("\u672a\u68c0\u6d4b");
            probeRow.setTextColor(colorTextDim);
        } else {
            int worst = worstLevel(lastProbe);
            probeRow.setText(worst == VERDICT_OK ? "\u901a\u8fc7\uff08\u672a\u88ab\u62d2\u7edd\uff09"
                    : (worst == VERDICT_WARN ? "\u90e8\u5206\u4e0d\u786e\u5b9a" : "\u5b58\u5728\u88ab\u62d2\u7edd"));
            probeRow.setTextColor(colorForLevel(worst));
        }
    }

    private int worstLevel(List<ProbeResult> results) {
        int worst = VERDICT_OK;
        for (int i = 0; i < results.size(); i++) {
            worst = Math.max(worst, results.get(i).level);
        }
        return worst;
    }

    private void updateInspector() {
        if (inspectorChips == null || inspectorSummary == null) {
            return;
        }
        inspectorChips.removeAllViews();
        String cookie = currentCookie();
        LinkedHashMap<String, String> pairs = parseCookie(cookie);
        List<TextView> row = new ArrayList<TextView>();
        int presentKnown = 0;
        for (int i = 0; i < KNOWN_COOKIE_KEYS.length; i++) {
            String key = KNOWN_COOKIE_KEYS[i];
            String found = findKey(pairs, key);
            boolean present = found != null;
            if (present) {
                presentKnown++;
                row.add(chip(key + " \u2713", VERDICT_OK));
            } else {
                int level;
                if ("serviceToken".equals(key) || "passToken".equals(key)) {
                    level = VERDICT_WARN;
                } else {
                    level = VERDICT_WARN;
                }
                row.add(chip(key + " \u2717", level));
            }
            if (row.size() == 3) {
                addChipRow(inspectorChips, row);
                row = new ArrayList<TextView>();
            }
        }
        int other = pairs.size() - presentKnown;
        if (other > 0) {
            row.add(chip("\u5176\u4ed6 " + other + " \u4e2a\u952e", VERDICT_OK));
        }
        if (!row.isEmpty()) {
            addChipRow(inspectorChips, row);
        }
        if (cookie.isEmpty()) {
            inspectorSummary.setText("\u5c1a\u672a\u914d\u7f6e Cookie\u3002\u53ef\u7f51\u9875\u767b\u5f55\u540e\u8bfb\u53d6\u3001\u624b\u52a8\u7c98\u8d34\uff0c"
                    + "\u6216\u4f7f\u7528\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u7684 Cookie\u3002");
        } else {
            inspectorSummary.setText("\u5171 " + pairs.size() + " \u4e2a\u952e\uff0c" + cookie.length()
                    + " \u5b57\u7b26\uff08\u4ec5\u663e\u793a\u952e\u540d\uff0c\u4e0d\u663e\u793a\u4ee4\u724c\u503c\uff09\u3002");
        }
        boolean missingSts = !cookie.isEmpty() && findKey(pairs, "serviceToken") == null;
        if (stsWarning != null) {
            if (missingSts) {
                stsWarning.setVisibility(View.VISIBLE);
                stsWarning.setText("\u26a0 \u672a\u68c0\u6d4b\u5230 serviceToken\uff1aWebView \u767b\u5f55\u5f97\u5230\u7684\u662f\u7f51\u9875 Cookie\uff0c"
                        + "\u4e91 API \u53ef\u80fd\u9700\u8981 STS Cookie\uff08serviceToken\uff09\u3002\u8bf7\u70b9\u300c\u53bb i.mi.com \u5b8c\u6210 STS\u300d"
                        + "\u540e\u91cd\u65b0\u8bfb\u53d6\uff0c\u6216\u4f7f\u7528\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u7684 Cookie\u3002");
            } else {
                stsWarning.setVisibility(View.GONE);
            }
        }
    }

    private void updateStatusPill() {
        if (statusPill == null) {
            return;
        }
        boolean gate = cbGate != null && cbGate.isChecked();
        String cookie = currentCookie();
        String probeText;
        int pillColor;
        if (probing) {
            probeText = "\u4f53\u68c0:\u68c0\u6d4b\u4e2d";
            pillColor = colorAccent;
        } else if (lastProbe.isEmpty()) {
            probeText = "\u4f53\u68c0:\u672a\u68c0\u6d4b";
            pillColor = cookie.isEmpty() ? colorYellow : colorAccent;
        } else {
            int worst = worstLevel(lastProbe);
            probeText = worst == VERDICT_OK ? "\u4f53\u68c0:\u901a\u8fc7"
                    : (worst == VERDICT_WARN ? "\u4f53\u68c0:\u90e8\u5206\u5f02\u5e38" : "\u4f53\u68c0:\u5f02\u5e38");
            pillColor = colorForLevel(worst);
        }
        String textValue = "\u95e8\u7981:" + (gate ? "\u5f00" : "\u5173")
                + " \u00b7 Cookie:" + (cookie.isEmpty() ? "\u672a\u914d\u7f6e" : "\u5df2\u914d\u7f6e")
                + " \u00b7 " + probeText;
        statusPill.setText(textValue);
        statusPill.setContentDescription("\u72b6\u6001\uff1a" + textValue);
        statusPill.setBackground(roundBg(pillColor, dp(16), pillColor));
    }

    private void updateProbeUiState() {
        if (probeButton != null) {
            probeButton.setEnabled(!probing && !currentCookie().isEmpty());
        }
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
            capturedView.setText("\u5c1a\u672a\u6355\u83b7\u3002\u9700\u5148\u5728\u7cfb\u7edf\u8bbe\u7f6e\u91cc\u767b\u5f55\u5c0f\u7c73\u8d26\u53f7"
                    + "\u2192 \u6253\u5f00\u76f8\u518c\u7f16\u8f91\u5e76\u89e6\u53d1\u4e00\u6b21\u4e91\u7aef\u8bf7\u6c42\uff1b"
                    + "LSPosed \u6302\u94a9\u8fdb\u7a0b\u4f1a\u628a App \u81ea\u5df1\u53d6\u5230\u7684 Cookie \u81ea\u52a8\u4fdd\u5b58\u5230\u8fd9\u91cc\u3002");
            return;
        }
        LinkedHashMap<String, String> pairs = parseCookie(captured);
        StringBuilder keys = new StringBuilder();
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (keys.length() > 0) {
                keys.append(", ");
            }
            keys.append(e.getKey());
            if (keys.length() > 160) {
                keys.append("\u2026");
                break;
            }
        }
        capturedView.setText("\u5df2\u6355\u83b7\uff08" + captured.length() + " \u5b57\u7b26\uff09\uff0c\u952e\uff1a"
                + (keys.length() == 0 ? "\u2014" : keys.toString())
                + "\n\u4e3a\u5b89\u5168\u8d77\u89c1\u4e0d\u76f4\u63a5\u663e\u793a\u4ee4\u724c\u503c\uff1b"
                + "\u70b9\u300c\u586b\u5165\u6355\u83b7\u7684 Cookie\u300d\u53ef\u5c06\u5b8c\u6574 Cookie \u5199\u5165\u4e0a\u65b9\u8f93\u5165\u6846\u3002");
    }

    private void setActionStatus(String textValue) {
        if (actionStatus != null) {
            actionStatus.setText(textValue == null ? "" : textValue);
        }
        Log.i(TAG, "status: " + (textValue == null ? "" : textValue));
    }

    // ------------------------------------------------------------------
    // Cookie parsing / capture
    // ------------------------------------------------------------------

    private LinkedHashMap<String, String> parseCookie(String cookie) {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        if (cookie == null || cookie.isEmpty()) {
            return map;
        }
        String[] parts = cookie.split(";");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq <= 0 || eq >= part.length() - 1) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty() && !map.containsKey(key)) {
                map.put(key, value);
            }
        }
        return map;
    }

    private String findKey(LinkedHashMap<String, String> pairs, String wanted) {
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (e.getKey().equalsIgnoreCase(wanted)) {
                return e.getKey();
            }
        }
        return null;
    }

    private boolean hasCookieKey(String cookie, String wanted) {
        return findKey(parseCookie(cookie), wanted) != null;
    }

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
    // Probe result holder
    // ------------------------------------------------------------------

    private static final class ProbeResult {
        final String url;
        final int level;
        final int code;
        final String verdict;
        final String snippet;

        ProbeResult(String url, int level, int code, String verdict, String snippet) {
            this.url = url;
            this.level = level;
            this.code = code;
            this.verdict = verdict;
            this.snippet = snippet == null ? "" : snippet;
        }
    }

    // ------------------------------------------------------------------
    // Help text
    // ------------------------------------------------------------------

    private static String buildHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u3010\u4f7f\u7528\u6b65\u9aa4\u3011\n");
        sb.append("1. \u5b89\u88c5\u672c APK \u540e\u6253\u5f00 LSPosed \u7ba1\u7406\u5668 \u2192\u300c\u6a21\u5757\u300d\u2192 \u542f\u7528\u300c\u5f95\u5361\u4e00\u77ac LSPosed\u300d\u3002\n");
        sb.append("2. \u4f5c\u7528\u57df\uff1acom.miui.mediaeditor\uff08\u5fc5\u9009\uff0c\u7f16\u8f91\u5668\uff09\uff1b"
                + "\u82e5\u4f7f\u7528\u76f8\u673a\u300c\u5f95\u5361\u4e00\u77ac\u300d\u518d\u52fe\u9009 com.android.camera\uff1b"
                + "com.miui.gallery \u53ef\u9009\u3002\u4fdd\u5b58\u540e\u5efa\u8bae\u91cd\u542f\u624b\u673a\u4e00\u6b21\u3002\n");
        sb.append("3. Cookie\uff1a\u4efb\u9009\u4e00\u79cd\u6765\u6e90\uff1a\n");
        sb.append("   a) \u70b9\u300c\u6253\u5f00\u767b\u5f55\u9875\u300d\u5728\u7f51\u9875\u767b\u5f55\u5c0f\u7c73\u8d26\u53f7\uff0c"
                + "\u5b8c\u6210\u540e\u70b9\u300c\u53bb i.mi.com \u5b8c\u6210 STS\u300d\uff0c\u518d\u70b9\u300c\u8bfb\u53d6\u767b\u5f55 Cookie\u300d\uff1b\n");
        sb.append("   b) \u624b\u52a8\u7c98\u8d34 Cookie\uff1b\n");
        sb.append("   c) \u7cfb\u7edf\u8d26\u53f7\u767b\u5f55\u540e\u6253\u5f00\u76f8\u518c\u2192\u7f16\u8f91\u89e6\u53d1\u4e00\u6b21\u4e91\u7aef\u8bf7\u6c42\uff0c"
                + "\u6a21\u5757\u81ea\u52a8\u6355\u83b7 App \u53d6\u5230\u7684 STS Cookie\uff0c\u518d\u70b9\u300c\u586b\u5165\u6355\u83b7\u7684 Cookie\u300d\u3002\n");
        sb.append("4. \u70b9\u300c\u4fdd\u5b58\u8bbe\u7f6e\u300d\u3002\u4fee\u6539 Cookie/\u5f00\u5173\u540e\u8bf7\u5230\u7cfb\u7edf\u8bbe\u7f6e \u2192 \u5e94\u7528 \u2192 "
                + "\u300c\u76f8\u518c\u300d/\u300c\u5a92\u4f53\u7f16\u8f91\u5668\u300d/\u300c\u76f8\u673a\u300d\u2192 \u5f3a\u5236\u505c\u6b62\u540e\u91cd\u5f00\u3002\n");
        sb.append("5. \u53ef\u9009\uff1a\u70b9\u300c\u68c0\u6d4b Cookie \u6709\u6548\u6027\u300d\u505a\u53ea\u8bfb GET \u4f53\u68c0\uff08\u4e0d\u6d88\u8017\u914d\u989d\uff09\u3002\n\n");
        sb.append("\u3010\u8bf4\u660e\u4e0e\u9650\u5236\u3011\n");
        sb.append("\u00b7 \u672c\u6a21\u5757\u4e3a\u300c\u65e0\u5c5e\u6027\u4f2a\u88c5\u300d\u6a21\u5f0f\uff1a\u4e0d\u4fee\u6539/\u4e0d\u4f2a\u88c5\u4efb\u4f55\u7cfb\u7edf\u5c5e\u6027\uff0c"
                + "\u4e5f\u4e0d hook SystemProperties\u3002\u95e8\u7981\u89e3\u9501\u901a\u8fc7\u76f4\u63a5 hook App \u81ea\u8eab\u7684 Java \u5224\u5b9a\u65b9\u6cd5\u8fd4\u56de true\uff1a"
                + "\u7f16\u8f91\u5668 CN rt.a.g()/ah0.b.g()\u3001GLOBAL mx.a.g()/r21.e.b()\uff1b\u76f8\u673a LegendaryEnter.support()\u3001Te.c.W()\u3001h5()\u3002\n");
        sb.append("\u00b7 Cookie \u4ec5\u4fdd\u5b58\u5728\u672c\u673a\u6a21\u5757\u79c1\u6709\u5b58\u50a8\uff1b\u6a21\u5757\u4e0d\u4e0a\u4f20\u3001\u4e0d\u8bb0\u5f55\u4ee4\u724c\u503c"
                + "\uff08\u4e0b\u65b9\u4f53\u68c0\u4e5f\u53ea\u663e\u793a\u72b6\u6001/\u952e\u540d\uff09\u3002\u8bf7\u4e0d\u8981\u5c06 Cookie \u5206\u4eab\u7ed9\u4ed6\u4eba\u3002\n");
        sb.append("\u00b7 Cookie \u4f53\u68c0\u4ec5\u9a8c\u8bc1 Cookie \u662f\u5426\u88ab\u63a5\u53d7\uff1b\u4e91\u7aef\u6743\u76ca\uff08\u70b9\u6570/\u8bbe\u5907\u767d\u540d\u5355/"
                + "\u786c\u4ef6\u8ba4\u8bc1 token\uff09\u4ecd\u7531\u5c0f\u7c73\u670d\u52a1\u7aef\u6821\u9a8c\u3002\n");
        sb.append("\u00b7 WebView \u767b\u5f55\u5f97\u5230\u7684\u662f\u7f51\u9875 Cookie\uff0c\u53ef\u80fd\u4e0d\u542b serviceToken\uff1b"
                + "\u8bf7\u5148\u53bb i.mi.com \u5b8c\u6210 STS\uff0c\u6216\u7528\u7cfb\u7edf\u8d26\u53f7\u6355\u83b7\u3002\n");
        sb.append("\u00b7 \u7f16\u8f91\u5668\u4fa7\u65e0\u9700 Magisk\uff1b\u76f8\u673a\u300c\u5f95\u5361\u4e00\u77ac\u300d\u9700\u8981 Magisk CameraOnly "
                + "\u53d8\u4f53\uff08\u4ec5\u53ea\u8bfb\u6302\u8f7d\u56fd\u884c MiuiCamera.apk\uff0c\u65e0\u5c5e\u6027\u4fee\u6539\uff09+ \u672c\u6a21\u5757\u3002\n");
        sb.append("\u00b7 \u76f8\u673a\u9650\u5236\uff1a\u5382\u5546 HAL \u7684 legendary \u80fd\u529b\u4e0e\u8fd0\u884c\u65f6 legend_mode SDK "
                + "\u4e0d\u80fd\u7531 Java hook \u6dfb\u52a0\uff0c\u82e5 HAL \u4e0d\u652f\u6301\u5219\u5165\u53e3\u53ef\u80fd\u51fa\u73b0\u4f46\u5b9e\u9645\u6548\u679c\u7f3a\u5931\u3002\n");
        sb.append("\u00b7 \u82e5\u76f8\u518c\u4ecd\u4e0d\u663e\u793a\u5165\u53e3\uff1a\u9996\u6b21\u542f\u7528\u540e\u6e05\u9664\u76f8\u518c\u80fd\u529b\u7f13\u5b58\uff08\u8bbe\u7f6e\u2192\u5e94\u7528\u2192\u76f8\u518c\u2192"
                + "\u5b58\u50a8\u2192\u6e05\u9664\u7f13\u5b58/\u6570\u636e\uff09\uff1b\u76f8\u518c\u4e3a Flutter+Rust\uff0cRust \u5c42\u4f1a\u7f13\u5b58\u65e7\u7ed3\u679c\u3002\n");
        sb.append("\u00b7 CTA \u9690\u79c1\u534f\u8bae\u4e3a App \u6b63\u5e38\u5f39\u7a97\uff0c\u672c\u6a21\u5757\u6709\u610f\u4e0d\u5f3a\u5236\uff1b\u5165\u53e3\u663e\u793a\u53ea\u53d6\u51b3\u4e8e\u5224\u5b9a\u65b9\u6cd5\u3002\n");
        sb.append("\u00b7 \u672c\u9879\u76ee\u4e3a\u79bb\u7ebf\u9006\u5411\u7814\u7a76\u7528\u9014\uff0c\u672a\u5728\u771f\u673a\u6d4b\u8bd5\uff0c\u8bf7\u81ea\u884c\u627f\u62c5\u98ce\u9669\u3002\n");
        return sb.toString();
    }
}
