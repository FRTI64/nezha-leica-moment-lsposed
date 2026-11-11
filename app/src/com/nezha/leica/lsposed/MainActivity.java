package com.nezha.leica.lsposed;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Module UI v2.4.0 (no system-account cookie).
 *
 * <p>Cookie source is ONLY the user's own cookie: manual paste or the cookie
 * exported by the full-screen {@link LoginActivity} web login. The module never
 * reads, captures, stores or logs the app's system-account STS cookie.</p>
 *
 * <p>Fully programmatic UI (no XML). Network I/O runs on a background thread;
 * token values are never logged and never shown in probe results (only key
 * names / lengths / HTTP codes / redacted snippets).</p>
 */
public class MainActivity extends Activity {

    private static final String TAG = "LeicaMomentLSP";

    private static final int REQ_LOGIN = 1001;
    private static final int REQ_PICK_IMAGE = 1002;
    private static final int REQ_PERM_MEDIA = 1003;

    /** Literal kept as a constant so it is verifiable in the dex (also self-documenting). */
    private static final String ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT";

    /** Explicit LegendaryActivity component + fallbacks (obfuscation/alias variants). */
    private static final String LEGENDARY_PACKAGE = "com.miui.mediaeditor";
    private static final String[] LEGENDARY_COMPONENT_VARIANTS = new String[]{
            "com.miui.mediaeditor/com.miui.mediaeditor.legendary.LegendaryActivity",
            "com.miui.mediaeditor/.legendary.LegendaryActivity",
            "com.miui.mediaeditor/com.miui.mediaeditor.LegendaryActivity"
    };

    /** Generic gallery UA; the cloud API uses MiuiGallery/<versionCode>. */
    private static final String PROBE_UA = "MiuiGallery/210994048";

    /** Chrome-mobile UA for web-bound cookies (pass_ua=web) and 401 fallback. */
    private static final String WEB_UA = "Mozilla/5.0 (Linux; Android 15; 2312DRA50C "
            + "Build/AP3A.240905.015) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /** Read-only GET endpoints used by the Cookie health probe (never POST). */
    private static final String[] PROBE_ENDPOINTS = new String[]{
            "https://i.mi.com/status/lite/setting?type=1",
            "https://i.mi.com/status/lite/setting?type=2"
    };

    /** Real target API: AI cloud platform used by 二次增强 (list is read-only, no create/billing). */
    private static final String AI_PROBE_URL =
            "https://api.open.ai.xiaomi.com/aiPhotoCloud/api/legendV2/list";
    private static final String AI_PROBE_LABEL =
            "\u53c2\u8003\uff1aAI \u4e91\u5e73\u53f0\uff08\u57fa\u5740\u7531\u4e91\u7aef\u914d\u7f6e\u52a8\u6001\u4e0b\u53d1\uff0c\u53ef\u80fd 404\uff09";
    /** Desktop Chrome UA fallback for web-bound cookies on the AI-platform POST probe. */
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int KIND_MICLOUD = 0;
    private static final int KIND_AI = 1;

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
    // Views / state
    // ------------------------------------------------------------------
    private LinearLayout root;
    private TextView statusPill;
    private TextView gateRow;
    private TextView cookieRow;
    private TextView stsRow;
    private TextView probeRow;
    private EditText cookieEdit;
    private LinearLayout inspectorChips;
    private TextView inspectorSummary;
    private TextView stsWarning;
    private TextView sourceHintView;
    private final LinkedHashMap<String, String> cookieSources = new LinkedHashMap<String, String>();
    private String cookieDupKeys = "";
    private TextView actionStatus;
    private Button probeButton;
    private TextView probeStatus;
    private LinearLayout probeResults;
    private TextView probeAggregate;
    private TextView cloudTraceView;
    private TextView cloudCaptureView;
    private TextView forceLaunchStatus;
    private String lastPickedUri = "";
    private String lastLaunchComponent = "";
    private CheckBox cbGate;
    private CheckBox cbCookie;
    private CheckBox cbLegendFallback;
    private CheckBox cbLegendCloudParams;
    private CheckBox cbCameraMakerNote;
    private CheckBox cbForceCnEndpoint;
    private TextView legendTargetView;
    private TextView cameraMakerNoteTargetView;
    private TextView cloudEndpointView;
    private TextView helpView;
    private Button helpToggle;

    private volatile boolean probing;
    private final List<ProbeResult> lastProbe = new ArrayList<ProbeResult>();
    /** Web-session self-test status returned by LoginActivity (MIN_VALUE = unknown). */
    private int lastSelfTestStatus = Integer.MIN_VALUE;
    private String lastSelfTestSnippet = "";

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
            loadCloudTrace();
            loadCloudCapture();
        } catch (Throwable t) {
            Log.e(TAG, "onCreate failed", t);
            setActionStatus("\u754c\u9762\u521d\u59cb\u5316\u5931\u8d25\uff1a" + t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            updateOverview();
            updateInspector();
            updateStatusPill();
            updateProbeUiState();
            loadCloudTrace();
            loadCloudCapture();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE) {
            try {
                if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                    setForceLaunchStatus("\u5df2\u53d6\u6d88\u9009\u62e9\u7167\u7247\u3002");
                    return;
                }
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
                String displayName = null;
                try {
                    displayName = uri.getLastPathSegment();
                } catch (Throwable ignored) {
                }
                launchLegendaryActivity(uri, displayName);
            } catch (Throwable t) {
                setForceLaunchStatus("\u5904\u7406\u9009\u62e9\u7684\u7167\u7247\u5931\u8d25\uff1a" + t);
            }
            return;
        }
        if (requestCode != REQ_LOGIN) {
            return;
        }
        try {
            if (resultCode != RESULT_OK || data == null) {
                setActionStatus("\u5168\u5c4f\u767b\u5f55\u9875\u5df2\u53d6\u6d88\u3002");
                return;
            }
            String cookie = data.getStringExtra(LoginActivity.EXTRA_COOKIE);
            lastSelfTestStatus = data.getIntExtra(LoginActivity.EXTRA_SELFTEST_STATUS,
                    Integer.MIN_VALUE);
            lastSelfTestSnippet = data.getStringExtra(LoginActivity.EXTRA_SELFTEST_SNIPPET);
            if (lastSelfTestSnippet == null) {
                lastSelfTestSnippet = "";
            }
            parseCookieSources(data.getStringExtra(LoginActivity.EXTRA_COOKIE_SOURCES));
            cookieDupKeys = data.getStringExtra(LoginActivity.EXTRA_COOKIE_DUP_KEYS);
            if (cookieDupKeys == null) {
                cookieDupKeys = "";
            }
            persistCookieSources();
            if (cookie == null || cookie.trim().isEmpty()) {
                setActionStatus("\u5168\u5c4f\u767b\u5f55\u9875\u672a\u8bfb\u53d6\u5230 Cookie\u3002");
                return;
            }
            cookie = cookie.trim();
            if (cookieEdit != null) {
                cookieEdit.setText(cookie);
            }
            updateOverview();
            updateInspector();
            updateStatusPill();
            updateProbeUiState();
            StringBuilder msg = new StringBuilder();
            msg.append("\u5df2\u4ece\u5168\u5c4f\u767b\u5f55\u9875\u8bfb\u53d6 Cookie\uff08")
                    .append(cookie.length()).append(" \u5b57\u7b26\uff09\u3002");
            if (lastSelfTestStatus >= 200 && lastSelfTestStatus < 300) {
                msg.append("\n\u7f51\u9875\u4f1a\u8bdd\u81ea\u68c0\uff1aHTTP ").append(lastSelfTestStatus)
                        .append("\uff08\u7f51\u9875\u4f1a\u8bdd\u6709\u6548\uff09\u3002")
                        .append("\n\u82e5\u6a21\u5757\u4f53\u68c0\u4ecd\u8fd4\u56de 401\uff0c\u8bf4\u660e\u8be5\u7f51\u9875 Cookie \u4e0e App "
                                + "\u4e91 API \u7684\u9274\u6743\u94fe\u8def\u4e0d\u540c\uff08\u9700 App \u539f\u751f\u8d26\u53f7\u94fe\u8def\uff0c"
                                + "\u672c\u6a21\u5757\u4e0d\u91c7\u7528\uff09\uff0c\u4e91 API \u53ef\u80fd\u4ecd\u62d2\u7edd\u8be5 Cookie\u3002");
            } else if (lastSelfTestStatus == 401 || lastSelfTestStatus == 403) {
                msg.append("\n\u7f51\u9875\u4f1a\u8bdd\u81ea\u68c0\uff1aHTTP ").append(lastSelfTestStatus)
                        .append("\uff08\u672a\u767b\u5f55/\u9700\u8981 STS\uff09\u3002\u8bf7\u56de\u5168\u5c4f\u767b\u5f55\u9875\u5b8c\u6210\u767b\u5f55/STS "
                                + "\u540e\u518d\u8bfb\u53d6 Cookie\u3002");
            } else if (lastSelfTestStatus == Integer.MIN_VALUE) {
                msg.append("\n\u7f51\u9875\u4f1a\u8bdd\u81ea\u68c0\uff1a\u672a\u6267\u884c\uff08\u53ef\u5728\u5168\u5c4f\u767b\u5f55\u9875\u70b9\u300c\u81ea\u68c0\u300d\uff09\u3002");
            } else {
                msg.append("\n\u7f51\u9875\u4f1a\u8bdd\u81ea\u68c0\uff1aHTTP ").append(lastSelfTestStatus)
                        .append("\uff08\u53ef\u80fd\u672a\u5b8c\u6210\u767b\u5f55\u6216\u7f51\u7edc\u5f02\u5e38\uff09\u3002");
            }
            if (!lastSelfTestSnippet.isEmpty()) {
                msg.append("\n\u81ea\u68c0\u54cd\u5e94\u6458\u8981\uff1a").append(lastSelfTestSnippet);
            }
            setActionStatus(msg.toString());
        } catch (Throwable t) {
            setActionStatus("\u5904\u7406\u767b\u5f55\u7ed3\u679c\u5931\u8d25\uff1a" + t);
        }
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
        buildForceLaunchCard();
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

        TextView note = text("\u672c\u6a21\u5757\u4e0d\u4f7f\u7528\u7cfb\u7edf\u8d26\u53f7 Cookie\uff1b"
                + "Cookie \u6765\u6e90\u4ec5\u9650\u7528\u6237\u624b\u52a8\u7c98\u8d34\u6216\u672c\u6a21\u5757\u5168\u5c4f\u7f51\u9875\u767b\u5f55\u3002",
                12, colorYellow, true);
        note.setPadding(dp(8), dp(6), dp(8), dp(8));
        note.setBackground(roundBg(colorFieldBg, dp(8), colorYellow));
        body.addView(note);

        gateRow = infoRow(body, "\u95e8\u7981\u89e3\u9501 Hook");
        cookieRow = infoRow(body, "Cookie \u914d\u7f6e");
        stsRow = infoRow(body, "STS / serviceToken");
        probeRow = infoRow(body, "Cookie \u4f53\u68c0");
    }

    private void buildCookieCard() {
        LinearLayout body = sectionCard("\u2461 \u8d26\u53f7\u4e0e Cookie\uff08\u7528\u6237 Cookie\uff09");

        TextView hint = text("\u63a8\u8350\u6d41\u7a0b\uff1a\u5168\u5c4f\u7f51\u9875\u767b\u5f55 \u2192 \u5b8c\u6210\uff08\u81ea\u52a8\u81ea\u68c0\u5e76\u56de\u586b Cookie\uff09"
                + " \u2192 \u4f53\u68c0 \u2192 \u6e05\u9664\u76f8\u518c\u80fd\u529b\u7f13\u5b58 \u2192 \u5f3a\u5236\u505c\u6b62 App \u2192 \u5b9e\u6d4b\u3002\n"
                + "\u82e5\u7f51\u9875\u4f1a\u8bdd\u81ea\u68c0 401\uff0c\u8bf7\u5148\u5728\u7f51\u9875\u4e2d\u5b8c\u6210\u767b\u5f55/STS\uff1b"
                + "\u82e5\u81ea\u68c0 200 \u4f46\u6a21\u5757\u4f53\u68c0 401\uff0c\u8bf4\u660e\u7f51\u9875 Cookie \u4e0e App \u4e91 API \u9274\u6743\u94fe\u8def\u4e0d\u540c\u3002", 12, colorTextDim, false);
        body.addView(hint);

        Button full = actionButton("\u5168\u5c4f\u767b\u5f55\u9875\uff08\u83b7\u53d6 Cookie\uff09",
                "\u6253\u5f00\u5168\u5c4f\u7f51\u9875\u767b\u5f55\u9875", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onFullLoginClick();
                    }
                });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(8);
        body.addView(full, flp);

        TextView manualLabel = text("\u7528\u6237 Cookie\uff08\u53ef\u7f16\u8f91 / \u7c98\u8d34\uff1b\u4e5f\u7531\u5168\u5c4f\u767b\u5f55\u9875\u81ea\u52a8\u56de\u586b\uff09",
                13, colorText, true);
        manualLabel.setPadding(0, dp(12), 0, dp(4));
        body.addView(manualLabel);

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

        sourceHintView = text("", 11, colorTextDim, false);
        sourceHintView.setTextIsSelectable(true);
        sourceHintView.setPadding(0, dp(2), 0, 0);
        body.addView(sourceHintView);

        stsWarning = text("", 12, colorYellow, true);
        stsWarning.setPadding(dp(8), dp(8), dp(8), dp(8));
        stsWarning.setVisibility(View.GONE);
        stsWarning.setBackground(roundBg(colorFieldBg, dp(8), colorYellow));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(8);
        body.addView(stsWarning, wlp);

        LinearLayout row1 = buttonRow(body);
        addRowButton(row1, "\u590d\u5236 Cookie", "\u590d\u5236\u5f53\u524d Cookie \u5230\u526a\u8d34\u677f", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onCopyClick();
                    }
                });
        addRowButton(row1, "\u4fdd\u5b58\u8bbe\u7f6e", "\u4fdd\u5b58 Cookie \u4e0e\u5f00\u5173", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onSaveClick();
                    }
                });

        LinearLayout row2 = buttonRow(body);
        addRowButton(row2, "\u6e05\u7a7a Cookie", "\u6e05\u7a7a\u7528\u6237 Cookie", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onClearClick();
                    }
                });
        addRowButton(row2, "\u5237\u65b0\u72b6\u6001", "\u5237\u65b0\u72b6\u6001\u4e0e\u7ec4\u6210\u68c0\u67e5", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        updateOverview();
                        updateInspector();
                        updateStatusPill();
                        updateProbeUiState();
                        setActionStatus("\u5df2\u5237\u65b0\u72b6\u6001\u3002");
                    }
                });

        actionStatus = text("", 12, colorTextDim, false);
        actionStatus.setTextIsSelectable(true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(10);
        body.addView(actionStatus, alp);
    }

    private void buildForceLaunchCard() {
        LinearLayout body = sectionCard("\u2461.5 \u5f3a\u5236\u542f\u52a8\u4e8c\u6b21\u589e\u5f3a\uff08\u6392\u969c\uff09");

        TextView hint = text("\u76f8\u518c\u5165\u53e3\u672a\u663e\u793a\u65f6\uff0c\u7528\u8fd9\u91cc\u76f4\u63a5\u6253\u5f00\u5f95\u5361\u4e00\u77ac\u5904\u7406\u9875\uff1b"
                + "\u8bf7\u9009\u4e00\u5f20\u521a\u62cd\u3001\u672a\u7f16\u8f91\u7684\u4e00\u77ac\u7167\u7247\uff0c\u8fdb\u5165\u540e\u70b9\u300c\u5f00\u59cb\u5904\u7406\u300d\u3002", 12, colorTextDim, false);
        body.addView(hint);

        LinearLayout row1 = buttonRow(body);
        addRowButton(row1, "\u9009\u62e9\u7167\u7247\u5e76\u542f\u52a8", "\u9009\u62e9\u4e00\u5f20\u7167\u7247\u5e76\u6253\u5f00\u5f95\u5361\u4e00\u77ac\u5904\u7406\u9875", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onPickPhotoClick();
                    }
                });
        addRowButton(row1, "\u7528\u76f8\u518c\u6700\u65b0\u7167\u7247\u542f\u52a8", "\u8bfb\u53d6\u76f8\u518c\u6700\u65b0\u7167\u7247\u5e76\u542f\u52a8", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onLatestPhotoClick();
                    }
                });

        LinearLayout row2 = buttonRow(body);
        addRowButton(row2, "\u590d\u5236 adb \u542f\u52a8\u547d\u4ee4", "\u590d\u5236 am start \u547d\u4ee4\u7528\u4e8e\u624b\u52a8\u6392\u969c", true,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onCopyAdbCommandClick();
                    }
                });

        forceLaunchStatus = text("", 11, colorTextDim, false);
        forceLaunchStatus.setTextIsSelectable(true);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(8);
        body.addView(forceLaunchStatus, flp);

        TextView usage = text("\u4f7f\u7528\uff1a\u2460 \u62cd\u4e00\u5f20\u6b63\u5e38\u4eae\u5ea6\u3001\u672a\u7f16\u8f91\u7684\u5f95\u5361\u4e00\u77ac\u7167\u7247\uff1b"
                + "\u2461 \u5728\u6a21\u5757\u91cc\u9009\u62e9\u7167\u7247\u542f\u52a8\uff1b\u2462 \u5728\u624b\u673a\u4e0a\u70b9\u300c\u5f00\u59cb\u5904\u7406\u300d\uff1b"
                + "\u2463 \u56de\u672c\u9875\u70b9\u300c\u5237\u65b0\u4e91\u7aef\u8f68\u8ff9\u300d\u5e76\u300c\u590d\u5236\u8bca\u65ad\u4fe1\u606f\u300d\u3002", 11, colorTextDim, false);
        usage.setPadding(0, dp(6), 0, 0);
        body.addView(usage);

        TextView fallbackNote = text("\u5df2\u9ed8\u8ba4\u542f\u7528 legend \u7248\u672c\u56de\u843d\uff08force_legend_fallback\uff09\uff1a"
                + "\u65e0\u4e00\u77ac\u5143\u6570\u636e\u7684\u7167\u7247\u5c06\u8d70 cleaned upload \u515c\u5e95\uff0c"
                + "\u53ef\u80fd\u4ea7\u751f\u4e00\u6b21\u4e91\u8bf7\u6c42/\u6d88\u8017\u989d\u5ea6\uff1b\u82e5\u670d\u52a1\u7aef\u62d2\u7edd\u5c5e\u6b63\u5e38\u3002"
                + "\u53ef\u5728 \u2463 \u529f\u80fd\u5f00\u5173\u4e2d\u5173\u95ed\u3002", 11, colorYellow, false);
        fallbackNote.setPadding(0, dp(6), 0, 0);
        body.addView(fallbackNote);

        TextView paramsNote = text("v2.5.3\uff1a\u5df2\u542f\u7528 legend \u4e91\u7aef\u53c2\u6570\u8865\u5168"
                + "\uff08aperture/focusX/focusY/oaid \u4e3a\u7a7a\u65f6\u81ea\u52a8\u8865\u9ed8\u8ba4\u503c\uff09\uff1b"
                + "AI \u4e91\u8bf7\u6c42\u7684\u54cd\u5e94 body \u7247\u6bb5\u5df2\u52a0\u5165\u300c\u5237\u65b0\u4e91\u7aef\u8f68\u8ff9\u300d\u3002",
                11, colorTextDim, false);
        paramsNote.setPadding(0, dp(4), 0, 0);
        body.addView(paramsNote);
    }

    private void buildProbeCard() {
        LinearLayout body = sectionCard("\u2462 Cookie \u4f53\u68c0\uff08\u53ea\u8bfb\u63a2\u6d4b\uff09");

        TextView desc = text("\u4f7f\u7528\u5f53\u524d\u7528\u6237 Cookie \u5bf9\u5c0f\u7c73\u4e91\u7aef\u53d1\u8d77\u53ea\u8bfb\u63a2\u6d4b"
                + "\uff08\u4e0d\u6d88\u8017\u914d\u989d\uff09\uff1a\u4e24\u4e2a i.mi.com micloud GET \u7aef\u70b9 + "
                + "\u4e00\u4e2a AI \u4e91\u5e73\u53f0 POST\uff08legendV2/list\uff0c\u53ea\u8bfb list\uff0c\u4e0d\u4f1a create/\u6536\u8d39\uff09\u3002"
                + "\u8bf7\u6c42\u5e26 Referer: https://i.mi.com/ \u4e0e Accept-Language\uff1b\u7f51\u9875 Cookie \u4f1a\u81ea\u52a8\u7528 Web UA\uff0c"
                + "HTTP 401 \u65f6\u6362\u53e6\u4e00\u79cd UA \u91cd\u8bd5\u4e00\u6b21\uff08AI \u5e73\u53f0\u53e6\u8bd5\u684c\u9762 Chrome UA\uff09\u3002"
                + "\u7ed3\u679c\u4ec5\u4f9b\u5224\u65ad Cookie \u662f\u5426\u88ab\u63a5\u53d7\uff1b\u4e91\u7aef\u6743\u76ca\u4ecd\u7531\u5c0f\u7c73\u670d\u52a1\u7aef\u6821\u9a8c\u3002", 12, colorTextDim, false);
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

        Button diag = actionButton("\u590d\u5236\u8bca\u65ad\u4fe1\u606f", "\u590d\u5236\u8bca\u65ad\u4fe1\u606f\uff08\u4e0d\u542b\u4ee4\u724c\u503c\uff09",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onCopyDiagnostics();
                    }
                });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(6);
        body.addView(diag, dlp);

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

        TextView traceLabel = text("\u4e91\u7aef\u8bf7\u6c42\u8f68\u8ff9\uff08\u6700\u8fd112\u6761\uff09", 13, colorText, true);
        traceLabel.setPadding(0, dp(12), 0, dp(4));
        body.addView(traceLabel);

        cloudTraceView = text("\uff08\u5c1a\u672a\u8bfb\u53d6\u3002\u5148\u6253\u5f00\u76f8\u518c\u7f16\u8f91\u5e76\u89e6\u53d1\u4e00\u6b21\u300c\u4e8c\u6b21\u589e\u5f3a\u300d\uff0c\u518d\u70b9\u300c\u5237\u65b0\u4e91\u7aef\u8f68\u8ff9\u300d\u3002\uff09",
                11, colorTextDim, false);
        cloudTraceView.setTextIsSelectable(true);
        cloudTraceView.setTypeface(Typeface.MONOSPACE);
        cloudTraceView.setPadding(dp(8), dp(8), dp(8), dp(8));
        cloudTraceView.setBackground(roundBg(colorFieldBg, dp(8), colorBorder));
        body.addView(cloudTraceView);

        Button refreshTrace = actionButton("\u5237\u65b0\u4e91\u7aef\u8f68\u8ff9", "\u5237\u65b0\u4e91\u7aef\u8bf7\u6c42\u8f68\u8ff9",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadCloudTrace();
                        setActionStatus("\u5df2\u5237\u65b0\u4e91\u7aef\u8f68\u8ff9\uff08\u82e5\u4e3a\u7a7a\uff0c\u8bf7\u5148\u5728\u76f8\u518c\u7f16\u8f91\u4e2d\u89e6\u53d1\u4e00\u6b21\u300c\u4e8c\u6b21\u589e\u5f3a\u300d\uff09\u3002");
                    }
                });
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.topMargin = dp(6);
        body.addView(refreshTrace, trlp);

        TextView traceHint = text("\u63d0\u793a\uff1a\u5148\u6253\u5f00\u76f8\u518c\u7f16\u8f91\u5e76\u89e6\u53d1\u4e00\u6b21\u300c\u4e8c\u6b21\u589e\u5f3a\u300d\uff0c"
                + "\u518d\u5237\u65b0/\u590d\u5236\u8bca\u65ad\uff0c\u53ef\u770b\u5230 App \u5b9e\u9645\u8bf7\u6c42\u7684\u4e91\u5730\u5740\u4e0e HTTP \u7801"
                + "\uff08\u4e0d\u542b\u4ee4\u724c\u3001\u4e0d\u542b\u67e5\u8be2\u53c2\u6570\uff09\u3002\n"
                + "\u8f68\u8ff9\u4ec5\u8bb0\u5f55\u8def\u5f84\u547d\u4e2d aiPhotoCloud / legend / hardwareAuth / hardwareNonce / auth/v1 / billing "
                + "\u4e14 host \u5c5e\u4e8e xiaomi/mi.com \u7684\u4e91\u8bf7\u6c42\u3002", 11, colorTextDim, false);
        traceHint.setPadding(0, dp(6), 0, 0);
        body.addView(traceHint);

        TextView captureLabel = text("\u4e91\u7aef\u62a5\u6587\u6355\u83b7\uff08\u6700\u8fd18\u6761\uff09", 13, colorText, true);
        captureLabel.setPadding(0, dp(12), 0, dp(4));
        body.addView(captureLabel);

        cloudCaptureView = text("\uff08\u6682\u65e0\u62a5\u6587\u3002\u5148\u89e6\u53d1\u4e00\u6b21\u300c\u4e8c\u6b21\u589e\u5f3a\u300d\uff0c\u518d\u5237\u65b0\u3002\uff09",
                11, colorTextDim, false);
        cloudCaptureView.setTextIsSelectable(true);
        cloudCaptureView.setTypeface(Typeface.MONOSPACE);
        cloudCaptureView.setPadding(dp(8), dp(8), dp(8), dp(8));
        cloudCaptureView.setBackground(roundBg(colorFieldBg, dp(8), colorBorder));
        body.addView(cloudCaptureView);

        Button refreshCapture = actionButton("\u5237\u65b0\u4e91\u7aef\u62a5\u6587", "\u5237\u65b0\u4e91\u7aef\u62a5\u6587\u6355\u83b7",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadCloudCapture();
                    }
                });
        LinearLayout.LayoutParams rclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rclp.topMargin = dp(6);
        body.addView(refreshCapture, rclp);

        TextView captureNote = text("\u4ec5\u8bb0\u5f55 create \u8bf7\u6c42\u4f53\u4e0e\u670d\u52a1\u7aef\u54cd\u5e94\u4f53\uff0c"
                + "\u672c\u5730\u4fdd\u5b58\uff08\u6700\u540e8\u6761\uff09\uff0c\u4e0d\u4e0a\u4f20\u3002", 11, colorTextDim, false);
        captureNote.setPadding(0, dp(4), 0, 0);
        body.addView(captureNote);

        TextView note = text("\u63a2\u6d4b\u7aef\u70b9\u4e3a\u53ea\u8bfb\u63a2\u6d4b\uff0c\u53ef\u80fd\u968f\u670d\u52a1\u7aef\u8c03\u6574\uff1b"
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
        cbCookie.setText("\u542f\u7528 Cookie \u6ce8\u5165\uff08\u4ec5\u4f7f\u7528\u7528\u6237 Cookie\uff1b\u4e3a\u7a7a\u65f6\u4e0d\u6ce8\u5165\u3001\u4fdd\u6301\u539f\u6837\uff09");
        cbCookie.setTextSize(13);
        cbCookie.setTextColor(colorText);
        cbCookie.setContentDescription("\u542f\u7528 Cookie \u6ce8\u5165\u5f00\u5173");
        body.addView(cbCookie);

        cbLegendFallback = new CheckBox(this);
        cbLegendFallback.setText("\u542f\u7528 legend \u7248\u672c\u56de\u843d\u6ce8\u5165\uff08\u65e0\u4e00\u77ac maker note \u7684\u7167\u7247\u8d70 cleaned upload \u515c\u5e95\uff1b"
                + "\u9ad8\u98ce\u9669\uff1a\u53ef\u80fd\u4ea7\u751f\u4e00\u6b21\u4e91\u8bf7\u6c42/\u6d88\u8017\u989d\u5ea6\uff0c\u670d\u52a1\u7aef\u53ef\u80fd\u62d2\u7edd\uff09");
        cbLegendFallback.setTextSize(13);
        cbLegendFallback.setTextColor(colorText);
        cbLegendFallback.setContentDescription("\u542f\u7528 legend \u7248\u672c\u56de\u843d\u6ce8\u5165\u5f00\u5173");
        body.addView(cbLegendFallback);

        cbLegendCloudParams = new CheckBox(this);
        cbLegendCloudParams.setText("\u542f\u7528 legend \u4e91\u7aef\u53c2\u6570\u8865\u5168\uff08CreateLegendV2TaskParams \u7684 aperture/focusX/focusY/oaid "
                + "\u4e3a\u7a7a\u65f6\u8865\u9ed8\u8ba4\u503c\uff1b\u54cd\u5e94 body \u7247\u6bb5\u4f1a\u52a0\u5165\u4e91\u7aef\u8f68\u8ff9\uff09");
        cbLegendCloudParams.setTextSize(13);
        cbLegendCloudParams.setTextColor(colorText);
        cbLegendCloudParams.setContentDescription("\u542f\u7528 legend \u4e91\u7aef\u53c2\u6570\u8865\u5168\u5f00\u5173");
        body.addView(cbLegendCloudParams);

        cbCameraMakerNote = new CheckBox(this);
        cbCameraMakerNote.setText("\u540c\u6b65 CN \u76f8\u673a MakerNote \u6807\u7b7e\uff08Moment*\uff0c\u6269\u5c55\u76f8\u673a tag map\uff1b"
                + "\u9700\u5f3a\u5236\u505c\u6b62\u76f8\u673a/\u91cd\u542f\u751f\u6548\uff09");
        cbCameraMakerNote.setTextSize(13);
        cbCameraMakerNote.setTextColor(colorText);
        cbCameraMakerNote.setContentDescription("\u540c\u6b65 CN \u76f8\u673a MakerNote \u6807\u7b7e\u5f00\u5173");
        body.addView(cbCameraMakerNote);

        cbForceCnEndpoint = new CheckBox(this);
        cbForceCnEndpoint.setText("\u5f3a\u5236\u4f7f\u7528 CN \u4e91\u7aef\u70b9 (api.open.ai.xiaomi.com)\uff08A/B\uff1a"
                + "\u5f00\u542f\u65f6\u5c06 intl \u4e91\u5730\u5740\u91cd\u5199\u4e3a CN\uff1b\u5173\u95ed\u5219\u4f7f\u7528\u56fd\u9645\u7aef\u70b9\uff09");
        cbForceCnEndpoint.setTextSize(13);
        cbForceCnEndpoint.setTextColor(colorText);
        cbForceCnEndpoint.setContentDescription("\u5f3a\u5236\u4f7f\u7528 CN \u4e91\u7aef\u70b9\u5f00\u5173");
        body.addView(cbForceCnEndpoint);

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

        legendTargetView = text("legend_fallback_target=\uff08\u7b49\u5f85\u6302\u94a9\u8fdb\u7a0b\u4e0a\u62a5\uff09",
                11, colorTextDim, false);
        legendTargetView.setTextIsSelectable(true);
        legendTargetView.setPadding(0, dp(6), 0, 0);
        body.addView(legendTargetView);

        cameraMakerNoteTargetView = text("camera_makernote=\uff08\u7b49\u5f85\u76f8\u673a\u8fdb\u7a0b\u4e0a\u62a5\uff09",
                11, colorTextDim, false);
        cameraMakerNoteTargetView.setTextIsSelectable(true);
        cameraMakerNoteTargetView.setPadding(0, dp(4), 0, 0);
        body.addView(cameraMakerNoteTargetView);

        cloudEndpointView = text("cloud_endpoint=\uff08\u7b49\u5f85\u5a92\u4f53\u7f16\u8f91\u5668\u4e0a\u62a5\uff09",
                11, colorTextDim, false);
        cloudEndpointView.setTextIsSelectable(true);
        cloudEndpointView.setPadding(0, dp(4), 0, 0);
        body.addView(cloudEndpointView);
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
        b.setTextSize(12);
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

    private void onFullLoginClick() {
        try {
            Intent intent = new Intent(this, LoginActivity.class);
            startActivityForResult(intent, REQ_LOGIN);
            setActionStatus("\u6b63\u5728\u6253\u5f00\u5168\u5c4f\u767b\u5f55\u9875\u2026\u5b8c\u6210\u65f6\u4f1a\u81ea\u52a8\u81ea\u68c0"
                    + "\u5e76\u56de\u586b Cookie\uff1b\u4e5f\u53ef\u5728\u8be5\u9875\u9876\u90e8\u70b9\u300c\u81ea\u68c0\u300d\u624b\u52a8\u68c0\u67e5\u7f51\u9875\u4f1a\u8bdd\u3002");
        } catch (Throwable t) {
            Log.e(TAG, "start LoginActivity failed", t);
            setActionStatus("\u65e0\u6cd5\u6253\u5f00\u5168\u5c4f\u767b\u5f55\u9875\uff1a" + t
                    + "\n\u53ef\u624b\u52a8\u7c98\u8d34 Cookie\u3002");
        }
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
            setActionStatus("\u5df2\u590d\u5236 Cookie \u5230\u526a\u8d34\u677f\uff08\u6ce8\u610f\uff1a\u526a\u8d34\u677f\u5185\u5bb9\u53ef\u80fd\u88ab\u5176\u5b83\u5e94\u7528\u8bfb\u53d6\uff09\u3002");
        } catch (Throwable t) {
            setActionStatus("\u590d\u5236\u5931\u8d25\uff1a" + t);
        }
    }

    private void onSaveClick() {
        String cookie = currentCookie();
        SharedPreferences p = ModuleConfig.prefs(this);
        boolean ok;
        try {
            ok = p.edit()
                    .putString(ModuleConfig.KEY_COOKIE, cookie)
                    .putBoolean(ModuleConfig.KEY_ENABLE_GATE_BYPASS, cbGate.isChecked())
                    .putBoolean(ModuleConfig.KEY_ENABLE_COOKIE, cbCookie.isChecked())
                    .putBoolean(ModuleConfig.KEY_FORCE_CTA, false)
                    .putBoolean(ModuleConfig.KEY_FORCE_LEGEND_FALLBACK,
                            cbLegendFallback != null && cbLegendFallback.isChecked())
                    .putBoolean(ModuleConfig.KEY_FORCE_LEGEND_CLOUD_PARAMS,
                            cbLegendCloudParams != null && cbLegendCloudParams.isChecked())
                    .putBoolean(ModuleConfig.KEY_SYNC_CN_CAMERA_MAKERNOTE,
                            cbCameraMakerNote != null && cbCameraMakerNote.isChecked())
                    .putBoolean(ModuleConfig.KEY_FORCE_CN_CLOUD_ENDPOINT,
                            cbForceCnEndpoint != null && cbForceCnEndpoint.isChecked())
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
                + "\u8bf7\u5f3a\u5236\u505c\u6b62\u5bf9\u5e94 App \u540e\u91cd\u65b0\u6253\u5f00\u3002");
        Toast.makeText(this, "\u4fdd\u5b58\u6210\u529f", Toast.LENGTH_SHORT).show();
    }

    private void onClearClick() {
        try {
            if (cookieEdit != null) {
                cookieEdit.setText("");
            }
            ModuleConfig.prefs(this).edit().remove(ModuleConfig.KEY_COOKIE).apply();
            lastSelfTestStatus = Integer.MIN_VALUE;
            lastSelfTestSnippet = "";
            updateOverview();
            updateInspector();
            updateStatusPill();
            updateProbeUiState();
            setActionStatus("\u5df2\u6e05\u7a7a\u7528\u6237 Cookie\u3002");
        } catch (Throwable t) {
            setActionStatus("\u6e05\u7a7a\u5931\u8d25\uff1a" + t);
        }
    }

    // ------------------------------------------------------------------
    // Forced LegendaryActivity launcher (bypass missing gallery entry)
    // ------------------------------------------------------------------

    private void onPickPhotoClick() {
        try {
            Intent intent = new Intent(ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_PICK_IMAGE);
            setForceLaunchStatus("\u8bf7\u9009\u62e9\u4e00\u5f20\u521a\u62cd\u3001\u672a\u7f16\u8f91\u7684\u5f95\u5361\u4e00\u77ac\u7167\u7247\u2026");
        } catch (Throwable t) {
            setForceLaunchStatus("\u6253\u5f00\u76f8\u518c\u9009\u62e9\u5668\u5931\u8d25\uff1a" + t
                    + "\n\u53ef\u7528\u300c\u590d\u5236 adb \u542f\u52a8\u547d\u4ee4\u300d\u624b\u52a8\u6392\u969c\u3002");
        }
    }

    private void onLatestPhotoClick() {
        String permission = mediaPermission();
        if (!hasPermission(permission)) {
            try {
                requestPermissions(new String[]{permission}, REQ_PERM_MEDIA);
                setForceLaunchStatus("\u6b63\u5728\u8bf7\u6c42\u8bfb\u53d6\u7167\u7247\u6743\u9650\uff08" + permission
                        + "\uff09\uff0c\u6388\u6743\u540e\u5c06\u81ea\u52a8\u7528\u76f8\u518c\u6700\u65b0\u7167\u7247\u542f\u52a8\u3002");
            } catch (Throwable t) {
                setForceLaunchStatus("\u8bf7\u6c42\u6743\u9650\u5931\u8d25\uff1a" + t);
            }
            return;
        }
        queryLatestPhotoAndLaunch();
    }

    private String mediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Manifest.permission.READ_MEDIA_IMAGES;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPermission(String permission) {
        try {
            return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM_MEDIA) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            queryLatestPhotoAndLaunch();
        } else {
            setForceLaunchStatus("\u672a\u6388\u4e88\u8bfb\u53d6\u7167\u7247\u6743\u9650\uff1a\u53ef\u6539\u7528\u300c\u9009\u62e9\u7167\u7247\u5e76\u542f\u52a8\u300d"
                    + "\uff08\u7cfb\u7edf\u6587\u4ef6\u9009\u62e9\u5668\u4e0d\u9700\u8981\u6b64\u6743\u9650\uff09\u3002");
        }
    }

    /** Queries the newest MediaStore image off the UI thread, then launches LegendaryActivity. */
    private void queryLatestPhotoAndLaunch() {
        setForceLaunchStatus("\u6b63\u5728\u67e5\u8be2\u76f8\u518c\u6700\u65b0\u7167\u7247\u2026");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Uri uri = null;
                String name = null;
                Cursor cursor = null;
                try {
                    String[] projection = new String[]{
                            MediaStore.Images.Media._ID,
                            MediaStore.Images.Media.DISPLAY_NAME
                    };
                    String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";
                    cursor = getContentResolver().query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            projection, null, null, sort);
                    if (cursor != null && cursor.moveToFirst()) {
                        long id = cursor.getLong(0);
                        try {
                            name = cursor.getString(1);
                        } catch (Throwable ignored) {
                        }
                        uri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "MediaStore query failed: " + t);
                } finally {
                    if (cursor != null) {
                        try {
                            cursor.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                final Uri foundUri = uri;
                final String foundName = name;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (foundUri == null) {
                            setForceLaunchStatus("\u672a\u5728\u76f8\u518c\u4e2d\u627e\u5230\u7167\u7247\uff1a\u8bf7\u5148\u62cd\u4e00\u5f20\u7167\u7247\uff0c"
                                    + "\u6216\u7528\u300c\u9009\u62e9\u7167\u7247\u5e76\u542f\u52a8\u300d\u3002");
                            return;
                        }
                        launchLegendaryActivity(foundUri, foundName);
                    }
                });
            }
        }, "latest-photo-query").start();
    }

    /** Launches the exported LegendaryActivity with the image Uri, trying fallback component names. */
    private void launchLegendaryActivity(Uri uri, String displayName) {
        if (uri == null) {
            setForceLaunchStatus("\u7167\u7247 Uri \u4e3a\u7a7a\uff0c\u65e0\u6cd5\u542f\u52a8\u3002");
            return;
        }
        String lastError = "";
        for (int i = 0; i < LEGENDARY_COMPONENT_VARIANTS.length; i++) {
            String comp = LEGENDARY_COMPONENT_VARIANTS[i];
            try {
                ComponentName cn = ComponentName.unflattenFromString(comp);
                if (cn == null) {
                    continue;
                }
                Intent intent = new Intent();
                intent.setComponent(cn);
                intent.setData(uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                lastPickedUri = uri.toString();
                lastLaunchComponent = comp;
                String scheme = uri.getScheme() == null ? "?" : uri.getScheme();
                setForceLaunchStatus("\u5df2\u542f\u52a8\uff1a" + comp
                        + "\nuri scheme=" + scheme
                        + (displayName == null || displayName.isEmpty() ? "" : "\uff08" + displayName + "\uff09")
                        + "\n\u8bf7\u5728\u5f95\u5361\u4e00\u77ac\u9875\u70b9\u300c\u5f00\u59cb\u5904\u7406\u300d\uff0c"
                        + "\u7136\u540e\u56de\u672c\u9875\u70b9\u300c\u5237\u65b0\u4e91\u7aef\u8f68\u8ff9\u300d\u3002");
                return;
            } catch (ActivityNotFoundException e) {
                lastError = "ActivityNotFound: " + comp;
            } catch (SecurityException e) {
                lastError = "SecurityException: " + comp;
            } catch (Throwable t) {
                lastError = comp + " -> " + t;
            }
        }
        setForceLaunchStatus("\u542f\u52a8\u5f95\u5361\u4e00\u77ac\u9875\u5931\u8d25\uff08\u5df2\u5c1d\u8bd5\u591a\u4e2a\u7ec4\u4ef6\uff09\uff1a" + lastError
                + "\n\u53ef\u70b9\u300c\u590d\u5236 adb \u542f\u52a8\u547d\u4ee4\u300d\uff0c\u5728 adb shell \u4e2d\u624b\u52a8\u6267\u884c\u3002");
    }

    private void onCopyAdbCommandClick() {
        try {
            String uri = lastPickedUri;
            if (uri == null || uri.isEmpty()) {
                uri = "content://media/external/images/media/<id>";
            }
            String command = "am start -n " + LEGENDARY_COMPONENT_VARIANTS[0]
                    + " -d " + uri + " --grant-read-uri-permission";
            String full = "adb shell " + command;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb command", full));
            setForceLaunchStatus("\u5df2\u590d\u5236 adb \u547d\u4ee4\uff1a\n" + full
                    + "\n\n\uff08\u5982\u9700\u5b9e\u9645 Uri\uff0c\u8bf7\u5148\u7528\u4e0a\u9762\u4e24\u4e2a\u6309\u94ae\u9009\u62e9/\u8bfb\u53d6\u7167\u7247\uff09");
        } catch (Throwable t) {
            setForceLaunchStatus("\u590d\u5236 adb \u547d\u4ee4\u5931\u8d25\uff1a" + t);
        }
    }

    private void setForceLaunchStatus(String textValue) {
        if (forceLaunchStatus != null) {
            forceLaunchStatus.setText(textValue == null ? "" : textValue);
        }
        Log.i(TAG, "force-launch: " + (textValue == null ? "" : textValue));
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

    // ------------------------------------------------------------------
    // Cookie health probe (read-only GET, UA-adaptive)
    // ------------------------------------------------------------------

    private void onProbeClick() {
        final String cookie = currentCookie();
        if (cookie.isEmpty()) {
            setActionStatus("\u8bf7\u5148\u901a\u8fc7\u5168\u5c4f\u767b\u5f55\u9875\u6216\u624b\u52a8\u7c98\u8d34\u914d\u7f6e Cookie\u3002");
            return;
        }
        if (probing) {
            return;
        }
        probing = true;
        updateProbeUiState();
        updateOverview();
        if (probeStatus != null) {
            probeStatus.setText("\u68c0\u6d4b\u4e2d\u2026\uff08" + (PROBE_ENDPOINTS.length + 1)
                    + " \u4e2a\u7aef\u70b9\uff1b\u6700\u591a " + (TIMEOUT_MS / 1000) + " \u79d2/\u7aef\u70b9\uff09");
        }
        if (probeResults != null) {
            probeResults.removeAllViews();
        }
        if (probeAggregate != null) {
            probeAggregate.setText("");
        }

        final boolean webBound = cookie.toLowerCase(Locale.US).contains("pass_ua=web");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ProbeResult> results = new ArrayList<ProbeResult>();
                try {
                    for (int i = 0; i < PROBE_ENDPOINTS.length; i++) {
                        results.add(probeOne(PROBE_ENDPOINTS[i], cookie, webBound));
                    }
                    // Third probe: the REAL target API (read-only list; no create/billing requests).
                    results.add(probeAiPlatform(cookie, webBound));
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
                                    + " \u4e2a\u7aef\u70b9\uff08\u542b AI \u4e91\u5e73\u53f0 POST \u53ea\u8bfb list\uff09");
                        }
                    }
                });
            }
        }, "cookie-health-probe").start();
    }

    private ProbeResult probeOne(String endpoint, String cookie, boolean webBound) {
        String primaryUa = webBound ? WEB_UA : PROBE_UA;
        String altUa = webBound ? PROBE_UA : WEB_UA;
        String primaryLabel = webBound ? "Web UA" : "MiuiGallery UA";
        String altLabel = webBound ? "MiuiGallery UA" : "Web UA";

        RawFetch first = rawFetch(endpoint, cookie, primaryUa, false);
        if (first.hasError()) {
            return new ProbeResult(endpoint, VERDICT_WARN, -1,
                    networkErrorKind(first.error), "", KIND_MICLOUD, "");
        }
        if (first.code == 401) {
            RawFetch second = rawFetch(endpoint, cookie, altUa, false);
            if (!second.hasError() && second.code > 0 && second.code != 401) {
                return verdict(endpoint, cookie, second, altLabel,
                        "\uff08\u9996\u6b21 " + primaryLabel + " 401\uff0c\u6362\u7528 " + altLabel + " \u540e\u901a\u8fc7\uff09");
            }
            if (!second.hasError() && second.code == 401) {
                return new ProbeResult(endpoint, VERDICT_BAD, 401,
                        "\u51ed\u636e\u88ab\u62d2\u7edd\uff08HTTP 401\uff1bWeb UA \u4e0e MiuiGallery UA \u5747\u88ab\u62d2\u7edd\uff09",
                        sanitizeSnippet(redactCookieValues(first.body, cookie)),
                        KIND_MICLOUD, "web+gallery");
            }
        }
        return verdict(endpoint, cookie, first, primaryLabel, "");
    }

    private ProbeResult verdict(String endpoint, String cookie, RawFetch f,
                                String uaLabel, String extra) {
        String ua = uaLabel == null ? "" : uaLabel;
        String suffix = extra == null ? "" : extra;
        String snippet = sanitizeSnippet(redactCookieValues(f.body, cookie));
        if (f.code >= 200 && f.code < 300) {
            if (looksLikeLoginRequired(f.body)) {
                return new ProbeResult(endpoint, VERDICT_BAD, f.code,
                        "\u670d\u52a1\u7aef\u8981\u6c42\u91cd\u65b0\u767b\u5f55\uff08\u54cd\u5e94\u542b 401/\u767b\u5f55\u6807\u8bc6\uff1bUA\uff1a"
                                + ua + "\uff09" + suffix, snippet, KIND_MICLOUD, ua);
            }
            // HTTP 2xx without login markers/401 identifiers == AUTH PASS for micloud.
            // A business/param-error JSON body does not change the auth verdict.
            boolean paramError = looksLikeParamError(f.body);
            return new ProbeResult(endpoint, VERDICT_OK, f.code,
                    "\u672a\u88ab\u8981\u6c42\u91cd\u65b0\u767b\u5f55\uff08\u9274\u6743\u901a\u8fc7\uff0cHTTP 200\uff09"
                            + (paramError ? "\uff08\u4e1a\u52a1\u53c2\u6570\u4e0d\u8db3\uff0c\u4e0d\u5f71\u54cd\u9274\u6743\u7ed3\u8bba\uff09" : "")
                            + "\uff1bUA\uff1a" + ua + suffix,
                    snippet, KIND_MICLOUD, ua);
        }
        if (f.code == 301 || f.code == 302 || f.code == 303 || f.code == 307 || f.code == 308) {
            String loc = f.location == null ? "" : f.location.toLowerCase(Locale.US);
            if (loc.contains("login") || loc.contains("passport")
                    || loc.contains("signin") || loc.contains("servicelogin")) {
                return new ProbeResult(endpoint, VERDICT_BAD, f.code,
                        "\u672a\u767b\u5f55/\u51ed\u636e\u65e0\u6548\uff08\u91cd\u5b9a\u5411\u5230\u767b\u5f55\u9875\uff1bUA\uff1a" + ua + "\uff09"
                                + suffix, sanitizeSnippet(f.location), KIND_MICLOUD, ua);
            }
            return new ProbeResult(endpoint, VERDICT_WARN, f.code,
                    "HTTP \u91cd\u5b9a\u5411\uff08\u975e\u767b\u5f55\uff1bUA\uff1a" + ua + "\uff09" + suffix,
                    sanitizeSnippet(f.location), KIND_MICLOUD, ua);
        }
        if (f.code == 401 || f.code == 403) {
            return new ProbeResult(endpoint, VERDICT_BAD, f.code,
                    "\u51ed\u636e\u88ab\u62d2\u7edd\uff08HTTP " + f.code + "\uff1bUA\uff1a" + ua + "\uff09" + suffix,
                    snippet, KIND_MICLOUD, ua);
        }
        return new ProbeResult(endpoint, VERDICT_WARN, f.code,
                "HTTP " + f.code + "\uff08UA\uff1a" + ua + "\uff09" + suffix, snippet, KIND_MICLOUD, ua);
    }

    /**
     * Read-only POST to the REAL target API ({@code legendV2/list}, body "{}").
     * Never sends create/billing requests. Web-bound cookies try the desktop
     * Chrome UA once on the first 401 (in addition to mobile Web UA / gallery UA).
     */
    private ProbeResult probeAiPlatform(String cookie, boolean webBound) {
        String primaryUa = webBound ? WEB_UA : PROBE_UA;
        String primaryLabel = webBound ? "Web UA" : "MiuiGallery UA";
        String altUa = webBound ? DESKTOP_UA : WEB_UA;
        String altLabel = webBound ? "Desktop Chrome UA" : "Web UA";

        RawFetch first = rawFetch(AI_PROBE_URL, cookie, primaryUa, true);
        if (first.hasError()) {
            return new ProbeResult(AI_PROBE_URL, VERDICT_WARN, -1,
                    AI_PROBE_LABEL + networkErrorKind(first.error), "", KIND_AI, "");
        }
        if (first.code == 401) {
            RawFetch second = rawFetch(AI_PROBE_URL, cookie, altUa, true);
            if (!second.hasError() && second.code > 0 && second.code != 401) {
                return aiVerdict(second, cookie, altLabel,
                        "\uff08\u9996\u6b21 " + primaryLabel + " 401\uff0c\u6362\u7528 " + altLabel + " \u540e\u901a\u8fc7\uff09");
            }
            if (!second.hasError() && second.code == 401) {
                return new ProbeResult(AI_PROBE_URL, VERDICT_BAD, 401,
                        AI_PROBE_LABEL + "\u62d2\u7edd\uff08HTTP 401\uff1bWeb/\u684c\u9762 Chrome/MiuiGallery UA \u5747\u88ab\u62d2\u7edd\uff09",
                        sanitizeSnippet(redactCookieValues(first.body, cookie)),
                        KIND_AI, "web+desktop+gallery");
            }
        }
        return aiVerdict(first, cookie, primaryLabel, "");
    }

    private ProbeResult aiVerdict(RawFetch f, String cookie, String uaLabel, String extra) {
        String ua = uaLabel == null ? "" : uaLabel;
        String suffix = extra == null ? "" : extra;
        String snippet = sanitizeSnippet(redactCookieValues(f.body, cookie));
        if (f.code >= 200 && f.code < 300) {
            if (looksLikeLoginRequired(f.body)) {
                return new ProbeResult(AI_PROBE_URL, VERDICT_BAD, f.code,
                        AI_PROBE_LABEL + "\u54cd\u5e94\u542b\u767b\u5f55/401 \u6807\u8bc6\uff08\u4ec5\u53c2\u8003\uff1bUA\uff1a" + ua + "\uff09" + suffix,
                        snippet, KIND_AI, ua);
            }
            return new ProbeResult(AI_PROBE_URL, VERDICT_OK, f.code,
                    AI_PROBE_LABEL + "\u8fd4\u56de HTTP 200\uff08\u4ec5\u53c2\u8003\uff0c\u4e0d\u53c2\u4e0e\u9274\u6743\u7ed3\u8bba\uff1bUA\uff1a"
                            + ua + "\uff09" + suffix, snippet, KIND_AI, ua);
        }
        if (f.code == 404 || f.code == 405) {
            return new ProbeResult(AI_PROBE_URL, VERDICT_WARN, f.code,
                    AI_PROBE_LABEL + "\u8def\u5f84/\u57fa\u5740\u4e0d\u5339\u914d\uff08\u4e0d\u4ee3\u8868 Cookie \u65e0\u6548\uff1bUA\uff1a" + ua + "\uff09" + suffix,
                    snippet, KIND_AI, ua);
        }
        if (f.code == 401 || f.code == 403) {
            return new ProbeResult(AI_PROBE_URL, VERDICT_BAD, f.code,
                    AI_PROBE_LABEL + "\u62d2\u7edd\uff08HTTP " + f.code + "\uff1b\u53c2\u8003\u4fe1\u53f7\uff0cUA\uff1a" + ua + "\uff09" + suffix,
                    snippet, KIND_AI, ua);
        }
        if (f.code == 301 || f.code == 302 || f.code == 303 || f.code == 307 || f.code == 308) {
            return new ProbeResult(AI_PROBE_URL, VERDICT_WARN, f.code,
                    AI_PROBE_LABEL + "\u8df3\u8f6c\uff08HTTP " + f.code + "\uff0c\u4ec5\u53c2\u8003\uff1bUA\uff1a" + ua + "\uff09" + suffix,
                    sanitizeSnippet(f.location), KIND_AI, ua);
        }
        return new ProbeResult(AI_PROBE_URL, VERDICT_WARN, f.code,
                AI_PROBE_LABEL + "\u8fd4\u56de HTTP " + f.code + "\uff08\u4ec5\u53c2\u8003\uff1bUA\uff1a" + ua + "\uff09" + suffix,
                snippet, KIND_AI, ua);
    }

    private RawFetch rawFetch(String endpoint, String cookie, String ua, boolean post) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod(post ? "POST" : "GET");
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Referer", "https://i.mi.com/");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            if (post) {
                byte[] body = "{}".getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.getOutputStream().write(body);
                conn.getOutputStream().flush();
                try {
                    conn.getOutputStream().close();
                } catch (Throwable ignored) {
                }
            }
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            InputStream is = null;
            try {
                is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            } catch (Throwable ignored) {
            }
            String bodyText = readLimited(is);
            boolean isWeb = ua != null && ua.startsWith("Mozilla");
            String uaKind = isWeb ? (ua.contains("Windows NT") ? "desktop" : "web") : "gallery";
            Log.i(TAG, "probe " + endpoint + " method=" + (post ? "POST" : "GET")
                    + " ua=" + uaKind + " -> HTTP " + code); // never log cookie/token values
            return new RawFetch(code, location, bodyText, null);
        } catch (Throwable t) {
            Log.i(TAG, "probe " + endpoint + " -> network error " + t.getClass().getSimpleName());
            return new RawFetch(-1, null, "", t);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private String networkErrorKind(Throwable t) {
        if (t instanceof java.net.UnknownHostException) {
            return "\u57df\u540d\u65e0\u6cd5\u89e3\u6790\uff08DNS/\u7f51\u7edc\u95ee\u9898\uff0c\u4e0d\u5224\u5b9a Cookie\uff09";
        }
        if (t instanceof java.net.SocketTimeoutException) {
            return "\u8fde\u63a5\u8d85\u65f6\uff08\u7f51\u7edc\u95ee\u9898\uff0c\u4e0d\u5224\u5b9a Cookie\uff09";
        }
        return "\u7f51\u7edc\u4e0d\u53ef\u8fbe\uff08" + (t == null ? "unknown" : t.getClass().getSimpleName())
                + "\uff0c\u4e0d\u5224\u5b9a Cookie\uff09";
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
        int mlOk = 0;
        int mlWarn = 0;
        int mlBad = 0;
        int aiLevel = -1;
        for (int i = 0; i < results.size(); i++) {
            ProbeResult r = results.get(i);
            if (r.kind == KIND_AI) {
                aiLevel = r.level;
            } else if (r.level == VERDICT_OK) {
                mlOk++;
            } else if (r.level == VERDICT_WARN) {
                mlWarn++;
            } else {
                mlBad++;
            }
        }
        StringBuilder agg = new StringBuilder();
        if (mlBad > 0) {
            agg.append("\u7ed3\u8bba\uff1amicloud \u8fd4\u56de 401/403\uff08\u6216\u91cd\u5b9a\u5411\u5230\u767b\u5f55\u9875\uff09"
                    + " \u2192 \u7f51\u9875 Cookie \u672a\u88ab\u5c0f\u7c73\u4e91\u9274\u6743\u63a5\u53d7\uff08\u89c1\u7ea2\u8272\u9879\uff09\u3002"
                    + "\u8bf7\u91cd\u65b0\u767b\u5f55/\u5b8c\u6210 STS \u540e\u91cd\u8bd5\uff1b\u5728\u4e0d\u4f7f\u7528\u7cfb\u7edf\u8d26\u53f7 Cookie \u7684\u524d\u63d0\u4e0b\uff0c"
                    + "\u670d\u52a1\u7aef\u9274\u6743\u95ee\u9898\u5ba2\u6237\u7aef\u65e0\u6cd5\u4fee\u590d\u3002");
        } else if (mlOk > 0) {
            agg.append("\u7ed3\u8bba\uff1aCookie \u5df2\u88ab\u5c0f\u7c73\u4e91\u9274\u6743\u63a5\u53d7\uff08micloud HTTP 200\uff09\u2192 \u9274\u6743\u901a\u8fc7\uff1b"
                    + "\u8bf7\u76f4\u63a5\u5b9e\u6d4b\u76f8\u518c\u7f16\u8f91\u300c\u4e8c\u6b21\u589e\u5f3a\u300d\uff0c\u80fd\u5426\u51fa\u56fe\u53d6\u51b3\u4e8e"
                    + "\u670d\u52a1\u7aef\u6743\u76ca/\u8bbe\u5907\u767d\u540d\u5355/\u786c\u4ef6\u8ba4\u8bc1 token\u3002");
            if (mlWarn > 0) {
                agg.append("\n\uff08\u53e6\u6709 ").append(mlWarn)
                        .append(" \u4e2a\u7aef\u70b9\u7f51\u7edc/\u975e\u9274\u6743\u95ee\u9898\uff0c\u4e0d\u5f71\u54cd\u9274\u6743\u7ed3\u8bba\u3002\uff09");
            }
            if (lastSelfTestStatus >= 200 && lastSelfTestStatus < 300) {
                agg.append("\n\u7f51\u9875\u4f1a\u8bdd\u81ea\u68c0 HTTP ").append(lastSelfTestStatus)
                        .append("\uff08\u7f51\u9875\u4f1a\u8bdd\u6709\u6548\uff09\uff0c\u4e0e\u9274\u6743\u901a\u8fc7\u7ed3\u8bba\u4e00\u81f4\u3002");
            }
        } else if (mlWarn > 0) {
            agg.append("\u7ed3\u8bba\uff1a\u65e0\u6cd5\u5224\u5b9a\uff08\u7f51\u7edc\u4e0d\u53ef\u8fbe/\u8d85\u65f6/\u7aef\u70b9\u53c2\u6570\u95ee\u9898\uff09"
                    + " \u2014\u2014 \u8fd9\u4e0d\u4ee3\u8868 Cookie \u65e0\u6548\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u91cd\u8bd5\u3002");
        } else {
            agg.append("\u7ed3\u8bba\uff1a\u672a\u6267\u884c\u6216\u65e0\u6cd5\u5224\u5b9a\u3002");
        }
        if (aiLevel >= 0) {
            agg.append("\n\u53c2\u8003\uff1aAI \u4e91\u5e73\u53f0\u884c\u4e0d\u53c2\u4e0e\u9274\u6743\u7ed3\u8bba\u2014\u2014\u5176\u57fa\u5740\u7531\u4e91\u7aef\u914d\u7f6e\u52a8\u6001\u4e0b\u53d1"
                    + "\uff08AIConfig.url_config\uff09\uff0c404/405 \u53ea\u8868\u793a\u8def\u5f84/\u57fa\u5740\u4e0d\u5339\u914d\uff0c\u4e0d\u4ee3\u8868 Cookie \u65e0\u6548\uff1b"
                    + "401/403 \u624d\u6709\u9274\u6743\u610f\u4e49\u3002");
            if (aiLevel == VERDICT_BAD) {
                agg.append("\n\u53c2\u8003\uff1a\u672c\u6b21 AI \u4e91\u5e73\u53f0\u884c\u8fd4\u56de 401/403\uff08\u4ec5\u4f9b\u53c2\u8003\uff0c\u4e0d\u6539\u53d8\u4e0a\u65b9 micloud \u9274\u6743\u7ed3\u8bba\uff09\u3002");
            }
        }
        if (cookieDupKeys != null && !cookieDupKeys.isEmpty()) {
            agg.append("\n\u6ce8\u610f\uff1a\u5408\u5e76 Cookie \u65f6\u53d1\u73b0\u8de8\u57df\u91cd\u590d\u952e\uff08")
                    .append(cookieDupKeys)
                    .append("\uff09\uff0c\u5df2\u6309 i.mi.com > api.open.ai.xiaomi.com > gallery.i.mi.com > account.xiaomi.com "
                            + "\u4f18\u5148\u7ea7\u53d6\u503c\uff1b\u82e5\u5f02\u5e38\u53ef\u80fd\u4e0e\u6b64\u6709\u5173\uff08\u4ec5\u663e\u793a\u952e\u540d\uff0c\u4e0d\u663e\u793a\u503c\uff09\u3002");
        }
        agg.append("\n\u6ce8\u610f\uff1a\u672c\u68c0\u6d4b\u4ec5\u9a8c\u8bc1 Cookie \u662f\u5426\u88ab\u63a5\u53d7\uff1b\u4e91\u7aef\u6743\u76ca\uff08\u70b9\u6570/\u8bbe\u5907\u767d\u540d\u5355/"
                + "\u786c\u4ef6\u8ba4\u8bc1 token\uff09\u7531\u5c0f\u7c73\u670d\u52a1\u7aef\u6821\u9a8c\uff0c\u65e0\u6cd5\u901a\u8fc7\u672c\u68c0\u6d4b\u9a8c\u8bc1\u3002"
                + "\u63a2\u6d4b\u7aef\u70b9\u4e3a\u53ea\u8bfb\uff0c\u53ef\u80fd\u968f\u670d\u52a1\u7aef\u8c03\u6574\u3002");
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

    private boolean looksLikeLoginRequired(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.US).replace(" ", "");
        return lower.contains("\"r\":401") || lower.contains("servicelogin")
                || lower.contains("\"code\":70016") || containsLoginMarker(body);
    }

    private boolean looksLikeParamError(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.US);
        return lower.contains("\u53c2\u6570") || lower.contains("\"code\":10008")
                || lower.contains("\"code\":10016") || lower.contains("is empty")
                || lower.contains("parameter");
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
        loadCookieSources();
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
        if (cbLegendFallback != null) {
            cbLegendFallback.setChecked(p.getBoolean(ModuleConfig.KEY_FORCE_LEGEND_FALLBACK,
                    ModuleConfig.DEFAULT_FORCE_LEGEND_FALLBACK));
            cbLegendFallback.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateOverview();
                    updateStatusPill();
                }
            });
        }
        if (cbLegendCloudParams != null) {
            cbLegendCloudParams.setChecked(p.getBoolean(ModuleConfig.KEY_FORCE_LEGEND_CLOUD_PARAMS,
                    ModuleConfig.DEFAULT_FORCE_LEGEND_CLOUD_PARAMS));
            cbLegendCloudParams.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateOverview();
                    updateStatusPill();
                }
            });
        }
        if (cbForceCnEndpoint != null) {
            cbForceCnEndpoint.setChecked(p.getBoolean(ModuleConfig.KEY_FORCE_CN_CLOUD_ENDPOINT,
                    ModuleConfig.DEFAULT_FORCE_CN_CLOUD_ENDPOINT));
            cbForceCnEndpoint.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateOverview();
                    updateStatusPill();
                }
            });
        }
        if (cbCameraMakerNote != null) {
            cbCameraMakerNote.setChecked(p.getBoolean(ModuleConfig.KEY_SYNC_CN_CAMERA_MAKERNOTE,
                    ModuleConfig.DEFAULT_SYNC_CN_CAMERA_MAKERNOTE));
            cbCameraMakerNote.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updateOverview();
                    updateStatusPill();
                }
            });
        }
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
        if (legendTargetView != null) {
            String target = "";
            try {
                target = ModuleConfig.prefs(this)
                        .getString(ModuleConfig.KEY_LEGEND_FALLBACK_TARGET, "");
            } catch (Throwable ignored) {
            }
            legendTargetView.setText("legend_fallback_target="
                    + (target == null || target.isEmpty() ? "\uff08\u5c1a\u672a\u4e0a\u62a5\uff09" : target)
                    + "\uff08\u5a92\u4f53\u7f16\u8f91\u5668\u8fdb\u7a0b\u4e0a\u62a5\uff1aCN/GLOBAL/none\uff09");
        }
        if (cameraMakerNoteTargetView != null) {
            String target = "";
            try {
                target = ModuleConfig.prefs(this)
                        .getString(ModuleConfig.KEY_CAMERA_MAKERNOTE_TARGET, "");
            } catch (Throwable ignored) {
            }
            cameraMakerNoteTargetView.setText("camera_makernote="
                    + (target == null || target.isEmpty() ? "\uff08\u5c1a\u672a\u4e0a\u62a5\uff09" : target)
                    + "\uff08\u76f8\u673a\u8fdb\u7a0b\u4e0a\u62a5\uff1aCN/GLOBAL/none\uff09");
        }
        if (cloudEndpointView != null) {
            String target = "";
            try {
                target = ModuleConfig.prefs(this)
                        .getString(ModuleConfig.KEY_CLOUD_ENDPOINT, "");
            } catch (Throwable ignored) {
            }
            cloudEndpointView.setText("cloud_endpoint="
                    + (target == null || target.isEmpty() ? "\uff08\u5c1a\u672a\u4e0a\u62a5\uff09" : target)
                    + "\uff08\u5a92\u4f53\u7f16\u8f91\u5668\u8fdb\u7a0b\u4e0a\u62a5\uff1b\u91cd\u5199\u540e\u4e3a CN \u5730\u5740\uff09");
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
            boolean present = findKey(pairs, key) != null;
            if (present) {
                presentKnown++;
                row.add(chip(key + " \u2713", VERDICT_OK));
            } else {
                row.add(chip(key + " \u2717", VERDICT_WARN));
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
            inspectorSummary.setText("\u5c1a\u672a\u914d\u7f6e Cookie\u3002\u8bf7\u70b9\u300c\u5168\u5c4f\u767b\u5f55\u9875\u300d\u83b7\u53d6\uff0c\u6216\u624b\u52a8\u7c98\u8d34\u3002");
        } else {
            inspectorSummary.setText("\u5171 " + pairs.size() + " \u4e2a\u952e\uff0c" + cookie.length()
                    + " \u5b57\u7b26\uff08\u4ec5\u663e\u793a\u952e\u540d\uff0c\u4e0d\u663e\u793a\u4ee4\u724c\u503c\uff09\u3002");
        }
        if (sourceHintView != null) {
            if (cookie.isEmpty()) {
                sourceHintView.setVisibility(View.GONE);
            } else {
                sourceHintView.setVisibility(View.VISIBLE);
                StringBuilder hint = new StringBuilder();
                if (cookieSources.isEmpty()) {
                    hint.append("\u6765\u6e90\u57df\u540d\uff1a\u65e0 WebView \u6765\u6e90\u4fe1\u606f\uff08\u624b\u52a8\u7c98\u8d34\uff09\u3002");
                } else {
                    hint.append("\u6765\u6e90\u57df\u540d\uff1a").append(describeSources());
                    if (cookieDupKeys != null && !cookieDupKeys.isEmpty()) {
                        hint.append("\n\u26a0 \u53d1\u73b0\u8de8\u57df\u91cd\u590d\u952e\uff08\u5df2\u6309 i.mi.com > api.open.ai.xiaomi.com > gallery.i.mi.com > account.xiaomi.com "
                                + "\u4f18\u5148\u7ea7\u53d6\u503c\uff09\uff1a").append(cookieDupKeys);
                    }
                }
                sourceHintView.setText(hint.toString());
            }
        }
        boolean missingSts = !cookie.isEmpty() && !hasCookieKey(cookie, "serviceToken");
        if (stsWarning != null) {
            if (missingSts) {
                stsWarning.setVisibility(View.VISIBLE);
                stsWarning.setText("\u26a0 \u672a\u68c0\u6d4b\u5230 serviceToken\uff1a\u7f51\u9875 Cookie \u53ef\u80fd\u4e0d\u88ab\u4e91 API "
                        + "\u63a5\u53d7\uff08\u5e38\u89c1 HTTP 401\uff09\u3002\u8bf7\u5728\u5168\u5c4f\u767b\u5f55\u9875\u5b8c\u6210 i.mi.com STS\uff08\u6253\u5f00 i.mi.com\uff09"
                        + "\u540e\u91cd\u65b0\u8bfb\u53d6\uff1b\u82e5\u4ecd 401\uff0c\u8bf7\u7528\u300c\u590d\u5236\u8bca\u65ad\u4fe1\u606f\u300d\u67e5\u770b\u8be6\u60c5\u3002");
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

    private void setActionStatus(String textValue) {
        if (actionStatus != null) {
            actionStatus.setText(textValue == null ? "" : textValue);
        }
        Log.i(TAG, "status: " + (textValue == null ? "" : textValue));
    }

    // ------------------------------------------------------------------
    // Cookie parsing
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

    // ------------------------------------------------------------------
    // Result holders
    // ------------------------------------------------------------------

    private static final class ProbeResult {
        final String url;
        final int level;
        final int code;
        final String verdict;
        final String snippet;
        final int kind;
        final String uaKind;

        ProbeResult(String url, int level, int code, String verdict, String snippet) {
            this(url, level, code, verdict, snippet, KIND_MICLOUD, "");
        }

        ProbeResult(String url, int level, int code, String verdict, String snippet,
                    int kind, String uaKind) {
            this.url = url;
            this.level = level;
            this.code = code;
            this.verdict = verdict;
            this.snippet = snippet == null ? "" : snippet;
            this.kind = kind;
            this.uaKind = uaKind == null ? "" : uaKind;
        }
    }

    private static final class RawFetch {
        final int code;
        final String location;
        final String body;
        final Throwable error;

        RawFetch(int code, String location, String body, Throwable error) {
            this.code = code;
            this.location = location;
            this.body = body == null ? "" : body;
            this.error = error;
        }

        boolean hasError() {
            return error != null;
        }
    }

    // ------------------------------------------------------------------
    // Cloud request trace (URL path + HTTP code only; no tokens/query/body)
    // ------------------------------------------------------------------

    private void loadCloudTrace() {
        String trace = readCloudTraceString();
        if (cloudTraceView != null) {
            if (trace.isEmpty()) {
                cloudTraceView.setText("\uff08\u6682\u65e0\u8f68\u8ff9\u3002\u8bf7\u5148\u6253\u5f00\u76f8\u518c\u7f16\u8f91\u5e76\u89e6\u53d1\u4e00\u6b21\u300c\u4e8c\u6b21\u589e\u5f3a\u300d\uff0c\u518d\u70b9\u300c\u5237\u65b0\u4e91\u7aef\u8f68\u8ff9\u300d\u3002\uff09");
            } else {
                cloudTraceView.setText(trace);
            }
        }
    }

    private String readCloudTraceString() {
        try {
            Bundle result = getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_GET_CLOUD_TRACE, null, null);
            String trace = result == null ? "" : result.getString("trace");
            return trace == null ? "" : trace;
        } catch (Throwable t) {
            return "";
        }
    }

    private void loadCloudCapture() {
        String capture = readCloudCaptureString();
        if (cloudCaptureView != null) {
            if (capture.isEmpty()) {
                cloudCaptureView.setText("\uff08\u6682\u65e0\u62a5\u6587\u3002\u5148\u89e6\u53d1\u4e00\u6b21\u300c\u4e8c\u6b21\u589e\u5f3a\u300d\uff0c"
                        + "\u518d\u70b9\u300c\u5237\u65b0\u4e91\u7aef\u62a5\u6587\u300d\u3002\uff09");
            } else {
                cloudCaptureView.setText(capture);
            }
        }
    }

    private String readCloudCaptureString() {
        try {
            Bundle cfg = getContentResolver().call(ModuleConfig.providerUri(),
                    ModuleConfig.METHOD_GET_CONFIG, null, null);
            String capture = cfg == null ? "" : cfg.getString("cloud_capture");
            return capture == null ? "" : capture;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics (no token values)
    // ------------------------------------------------------------------

    private void onCopyDiagnostics() {
        try {
            String text = buildDiagnosticsText();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("LeicaMoment diagnostics", text));
            setActionStatus("\u5df2\u590d\u5236\u8bca\u65ad\u4fe1\u606f\uff08\u4ec5\u542b HTTP \u7801/\u952e\u540d/\u957f\u5ea6\uff0c"
                    + "\u4e0d\u542b\u4ee4\u724c\u503c\uff09\uff1a\n" + text);
            Toast.makeText(this, "\u8bca\u65ad\u4fe1\u606f\u5df2\u590d\u5236", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            setActionStatus("\u590d\u5236\u8bca\u65ad\u4fe1\u606f\u5931\u8d25\uff1a" + t);
        }
    }

    private String buildDiagnosticsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("LeicaMoment LSPosed v").append(ModuleConfig.MODULE_VERSION).append(" diagnostics\n");
        String cookie = currentCookie();
        LinkedHashMap<String, String> pairs = parseCookie(cookie);
        sb.append("Cookie: length=").append(cookie.length())
                .append(", keys=").append(keysOf(pairs)).append('\n');
        sb.append("Web self-test: ").append(selfTestLabel()).append('\n');
        sb.append("Interpretation: micloud HTTP 200 => auth pass (Cookie accepted by Xiaomi cloud); "
                + "business/param-error body does NOT change the auth verdict.\n");
        sb.append("AI platform row is reference only: its base URL is remote-config driven (AIConfig.url_config), "
                + "so 404/405 is a path/base mismatch, not an auth signal.\n");
        if (!cookieSources.isEmpty()) {
            sb.append("Cookie sources: ").append(describeSources()).append('\n');
        }
        if (cookieDupKeys != null && !cookieDupKeys.isEmpty()) {
            sb.append("Duplicate cookie names across domains: ").append(cookieDupKeys).append('\n');
        }
        sb.append("Probes:\n");
        if (lastProbe.isEmpty()) {
            sb.append(" - (not probed yet)\n");
        } else {
            for (int i = 0; i < lastProbe.size(); i++) {
                ProbeResult r = lastProbe.get(i);
                sb.append(" - ").append(r.kind == KIND_AI
                        ? "AI platform POST list (reference)" : "micloud GET").append(' ');
                sb.append(r.url).append(" -> ");
                if (r.code > 0) {
                    sb.append("HTTP ").append(r.code);
                } else {
                    sb.append("network error");
                }
                if (!r.uaKind.isEmpty()) {
                    sb.append(" [UA: ").append(r.uaKind).append(']');
                }
                sb.append(" [").append(levelLabel(r.level)).append("]\n");
            }
        }
        String legendTarget = "";
        try {
            legendTarget = ModuleConfig.prefs(this)
                    .getString(ModuleConfig.KEY_LEGEND_FALLBACK_TARGET, "");
        } catch (Throwable ignored) {
        }
        sb.append("legend_fallback_target=")
                .append(legendTarget == null || legendTarget.isEmpty() ? "unknown" : legendTarget)
                .append('\n');
        String cameraMakernote = "";
        try {
            cameraMakernote = ModuleConfig.prefs(this)
                    .getString(ModuleConfig.KEY_CAMERA_MAKERNOTE_TARGET, "");
        } catch (Throwable ignored) {
        }
        sb.append("camera_makernote=")
                .append(cameraMakernote == null || cameraMakernote.isEmpty()
                        ? "unknown" : cameraMakernote)
                .append('\n');
        String cloudEndpoint = "";
        try {
            cloudEndpoint = ModuleConfig.prefs(this)
                    .getString(ModuleConfig.KEY_CLOUD_ENDPOINT, "");
        } catch (Throwable ignored) {
        }
        sb.append("cloud_endpoint=")
                .append(cloudEndpoint == null || cloudEndpoint.isEmpty()
                        ? "unknown" : cloudEndpoint)
                .append('\n');
        String cloudTrace = readCloudTraceString();
        sb.append("Cloud request trace (last 12; method/host/path/code/cookie only, no query/token/body):\n");
        if (cloudTrace.isEmpty()) {
            sb.append(" - (none yet; open 相册编辑 and trigger 二次增强 first)\n");
        } else {
            String[] lines = cloudTrace.split("\n");
            for (int i = 0; i < lines.length; i++) {
                sb.append(" - ").append(lines[i]).append('\n');
            }
        }
        String cloudCapture = readCloudCaptureString();
        sb.append("Cloud payload capture (last 8; create request/response bodies, local only):\n");
        if (cloudCapture.isEmpty()) {
            sb.append(" - (none yet; trigger 二次增强 first)\n");
        } else {
            String[] capLines = cloudCapture.split("\n");
            for (int i = 0; i < capLines.length; i++) {
                sb.append(" - ").append(capLines[i]).append('\n');
            }
        }
        sb.append("Note: no token values included; only key names/lengths, HTTP codes, URL paths and selftest status.\n");
        return sb.toString();
    }

    private String keysOf(LinkedHashMap<String, String> pairs) {
        if (pairs.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String k : pairs.keySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(k);
        }
        return sb.toString();
    }

    private String describeSources() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookieSources.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append("<-").append(e.getValue());
        }
        return sb.toString();
    }

    private String selfTestLabel() {
        if (lastSelfTestStatus == Integer.MIN_VALUE) {
            return "not run";
        }
        if (lastSelfTestStatus > 0) {
            return "HTTP " + lastSelfTestStatus;
        }
        return "network error";
    }

    private String levelLabel(int level) {
        if (level == VERDICT_OK) {
            return "OK";
        }
        if (level == VERDICT_WARN) {
            return "WARN";
        }
        return "REJECTED";
    }

    private void parseCookieSources(String json) {
        cookieSources.clear();
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (k != null && !k.isEmpty() && v != null && !v.isEmpty()) {
                    cookieSources.put(k, v);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void persistCookieSources() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : cookieSources.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            ModuleConfig.prefs(this).edit()
                    .putString(ModuleConfig.KEY_COOKIE_SOURCES, o.toString())
                    .putString(ModuleConfig.KEY_COOKIE_DUP_KEYS, cookieDupKeys == null ? "" : cookieDupKeys)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void loadCookieSources() {
        try {
            SharedPreferences p = ModuleConfig.prefs(this);
            parseCookieSources(p.getString(ModuleConfig.KEY_COOKIE_SOURCES, "{}"));
            cookieDupKeys = p.getString(ModuleConfig.KEY_COOKIE_DUP_KEYS, "");
            if (cookieDupKeys == null) {
                cookieDupKeys = "";
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Help text
    // ------------------------------------------------------------------

    private static String buildHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u3010\u91cd\u8981\u524d\u63d0\u3011\n");
        sb.append("\u672c\u6a21\u5757\u4e0d\u4f7f\u7528\u7cfb\u7edf\u8d26\u53f7 Cookie\uff1bCookie \u6765\u6e90\u4ec5\u9650\u7528\u6237\u624b\u52a8\u7c98\u8d34\u6216"
                + "\u672c\u6a21\u5757\u5168\u5c4f\u7f51\u9875\u767b\u5f55\u3002\u6a21\u5757\u4e0d\u4f1a\u8bfb\u53d6/\u6355\u83b7/\u4fdd\u5b58\u5e94\u7528\u7684\u7cfb\u7edf\u8d26\u53f7 STS Cookie\u3002\n\n");
        sb.append("\u3010\u63a8\u8350\u6d41\u7a0b\u3011\n");
        sb.append("1. \u70b9\u300c\u5168\u5c4f\u767b\u5f55\u9875\uff08\u83b7\u53d6 Cookie\uff09\u300d\uff0c\u5728\u7f51\u9875\u4e2d\u5b8c\u6210\u5c0f\u7c73\u8d26\u53f7\u767b\u5f55\uff1b"
                + "\u82e5\u9700 STS\uff0c\u8bf7\u5728\u9875\u9762\u4e2d\u6253\u5f00 i.mi.com \u5b8c\u6210 STS\u3002\n");
        sb.append("2. \u70b9\u53f3\u4e0a\u89d2\u300c\u5b8c\u6210\u300d\uff1a\u9875\u9762\u4f1a\u81ea\u52a8\u8fd0\u884c\u4e00\u6b21\u300c\u7f51\u9875\u4f1a\u8bdd\u81ea\u68c0\u300d"
                + "\uff08evaluateJavascript fetch \u5230 i.mi.com/status/lite/setting\uff09\u5e76\u56de\u586b Cookie\uff1b"
                + "\u4e5f\u53ef\u968f\u65f6\u70b9\u9876\u90e8\u300c\u81ea\u68c0\u300d\u67e5\u770b\u7f51\u9875\u4f1a\u8bdd\u662f\u5426\u5df2\u767b\u5f55\u3002\n");
        sb.append("3. \u56de\u6a21\u5757\u540e\u70b9\u300c\u4f53\u68c0\u300d\uff08\u53ea\u8bfb GET\uff09\uff1b\u82e5 401\uff0c\u5148\u56de\u5168\u5c4f\u9875\u5b8c\u6210\u767b\u5f55/STS\u3002\n");
        sb.append("4. \u6e05\u9664\u76f8\u518c\u80fd\u529b\u7f13\u5b58\uff08\u8bbe\u7f6e\u2192\u5e94\u7528\u2192\u76f8\u518c\u2192\u5b58\u50a8\u2192\u6e05\u9664\u7f13\u5b58/\u6570\u636e\uff09\uff0c"
                + "\u5f3a\u5236\u505c\u6b62\u5a92\u4f53\u7f16\u8f91\u5668/\u76f8\u518c\u540e\u5b9e\u6d4b\u3002\n\n");
        sb.append("\u3010\u81ea\u68c0\u3001\u4f53\u68c0\u4e0e AI \u53c2\u8003\u884c\u3011\n");
        sb.append("\u00b7 \u7f51\u9875\u4f1a\u8bdd\u81ea\u68c0\uff08LoginActivity \u5185\uff0cevaluateJavascript + JS bridge\uff09\u7528\u7f51\u9875\u4f1a\u8bdd\u8bbf\u95ee"
                + " i.mi.com/status/lite/setting\uff1a401 = \u7f51\u9875\u672a\u767b\u5f55/\u9700 STS\uff1b200 = \u7f51\u9875\u4f1a\u8bdd\u6709\u6548\u3002\n");
        sb.append("\u00b7 micloud \u4f53\u68c0\uff08i.mi.com/status/lite/setting?type=1|2\uff09\uff1a**HTTP 2xx \u4e14\u65e0\u767b\u5f55/401 \u6807\u8bc6 = \u9274\u6743\u901a\u8fc7\uff08\u7eff\u8272\uff09**\uff1b"
                + "\u82e5\u54cd\u5e94\u662f\u4e1a\u52a1\u53c2\u6570\u4e0d\u8db3\uff0c\u4ec5\u6dfb\u52a0\u300c\u4e1a\u52a1\u53c2\u6570\u4e0d\u8db3\uff0c\u4e0d\u5f71\u54cd\u9274\u6743\u7ed3\u8bba\u300d\u6ce8\u91ca\uff0c\u4e0d\u964d\u7ea7\u4e3a\u9ec4\u8272\u3002\n");
        sb.append("\u00b7 \u300c\u53c2\u8003\uff1aAI \u4e91\u5e73\u53f0\u300dPOST \u884c\uff1a\u5b83\u7684\u57fa\u5740\u7531\u4e91\u7aef\u914d\u7f6e\u52a8\u6001\u4e0b\u53d1\uff08AIConfig.url_config\uff09\uff0c"
                + "\u6240\u4ee5 404/405 \u53ea\u8868\u793a\u8def\u5f84/\u57fa\u5740\u4e0d\u5339\u914d\uff08\u4e0d\u4ee3\u8868 Cookie \u65e0\u6548\uff09\uff0c\u4e0d\u53c2\u4e0e\u9274\u6743\u7ed3\u8bba\uff1b"
                + "\u82e5\u8be5\u884c\u8fd4\u56de 401/403 \u5219\u4ec5\u4f5c\u53c2\u8003\u3002\n");
        sb.append("\u00b7 \u805a\u5408\u7ed3\u8bba\uff1a\u4e24\u4e2a micloud \u7aef\u70b9 2xx \u2192 \u300c\u9274\u6743\u901a\u8fc7\u300d\uff0c\u8bf7\u76f4\u63a5\u5b9e\u6d4b\u76f8\u518c\u7f16\u8f91"
                + "\u300c\u4e8c\u6b21\u589e\u5f3a\u300d\uff1b\u4efb\u4e00 micloud 401/403 \u2192 \u7ea2\u8272\u7ed3\u8bba\uff1b\u7f51\u7edc\u95ee\u9898 \u2192 \u65e0\u6cd5\u5224\u5b9a\u3002"
                + "\u80fd\u5426\u51fa\u56fe\u53d6\u51b3\u4e8e\u670d\u52a1\u7aef\u6743\u76ca/\u8bbe\u5907\u767d\u540d\u5355/\u786c\u4ef6\u8ba4\u8bc1 token\u3002\n");
        sb.append("\u00b7 \u7f51\u9875 Cookie \u5408\u5e76\u4f18\u5148\u7ea7\uff1ai.mi.com > api.open.ai.xiaomi.com > gallery.i.mi.com > account.xiaomi.com > xiaomi.com\uff08\u540c\u540d\u952e\u4fdd\u7559\u9ad8\u4f18\u5148\u7ea7\uff09\uff1b"
                + "\u754c\u9762\u4f1a\u663e\u793a\u6765\u6e90\u57df\u540d\u4e0e\u8de8\u57df\u91cd\u590d\u952e\u63d0\u793a\uff08\u4ec5\u952e\u540d\uff09\u3002\n");
        sb.append("\u00b7 \u300c\u590d\u5236\u8bca\u65ad\u4fe1\u606f\u300d\u53ea\u590d\u5236\u7248\u672c\u3001HTTP \u7801\u3001UA \u7c7b\u578b\u3001\u81ea\u68c0\u72b6\u6001\u3001"
                + "Cookie \u952e\u540d\u4e0e\u957f\u5ea6\u3001\u6765\u6e90\u57df\u540d\uff0c\u4e0d\u5305\u542b\u4efb\u4f55\u4ee4\u724c\u503c\u3002\n\n");
        sb.append("\u3010\u5f3a\u5236\u542f\u52a8\u4e8c\u6b21\u589e\u5f3a\uff08\u6392\u969c\uff09\u3011\n");
        sb.append("\u00b7 \u76f8\u518c\u91cc\u6ca1\u6709\u300c\u4e8c\u6b21\u589e\u5f3a\u300d\u5165\u53e3\u65f6\uff0c\u53ef\u5728\u6a21\u5757\u7684\u300c\u2461.5 \u5f3a\u5236\u542f\u52a8\u4e8c\u6b21\u589e\u5f3a\uff08\u6392\u969c\uff09\u300d\u5361\u7247\u91cc"
                + "\u76f4\u63a5\u6253\u5f00\u5f95\u5361\u4e00\u77ac\u5904\u7406\u9875\uff08com.miui.mediaeditor.legendary.LegendaryActivity\uff09\u3002\n");
        sb.append("\u00b7 \u6b65\u9aa4\uff1a\u2460 \u62cd\u4e00\u5f20\u6b63\u5e38\u4eae\u5ea6\u3001\u672a\u7f16\u8f91\u7684\u5f95\u5361\u4e00\u77ac\u7167\u7247\uff1b\u2461 \u5728\u6a21\u5757\u91cc\u70b9\u300c\u9009\u62e9\u7167\u7247\u5e76\u542f\u52a8\u300d"
                + "\u6216\u300c\u7528\u76f8\u518c\u6700\u65b0\u7167\u7247\u542f\u52a8\u300d\uff1b\u2462 \u5728\u624b\u673a\u4e0a\u70b9\u300c\u5f00\u59cb\u5904\u7406\u300d\uff1b"
                + "\u2463 \u56de\u672c\u9875\u70b9\u300c\u5237\u65b0\u4e91\u7aef\u8f69\u8ff9\u300d\u5e76\u590d\u5236\u8bca\u65ad\u3002\n");
        sb.append("\u00b7 \u82e5\u542f\u52a8\u5931\u8d25\uff0c\u53ef\u590d\u5236 adb \u547d\u4ee4\uff08am start -n \u7ec4\u4ef6 -d <uri> --grant-read-uri-permission\uff09"
                + "\u5728 adb shell \u4e2d\u624b\u52a8\u6267\u884c\uff1b\u6a21\u5757\u4e0d\u4f1a\u4fee\u6539\u4efb\u4f55\u7cfb\u7edf\u5c5e\u6027\u3002\n");
        sb.append("\u00b7 v2.5.1 \u5df2\u9ed8\u8ba4\u542f\u7528 legend \u7248\u672c\u56de\u843d\u6ce8\u5165\uff1a\u65e0\u4e00\u77ac maker note \u7684\u7167\u7247\u5c06\u8d70 cleaned upload \u515c\u5e95\uff0c"
                + "\u53ef\u80fd\u4ea7\u751f\u4e00\u6b21\u4e91\u8bf7\u6c42/\u6d88\u8017\u989d\u5ea6\uff1b\u82e5\u670d\u52a1\u7aef\u62d2\u7edd\u5c5e\u6b63\u5e38\u3002\n");
        sb.append("\u3010\u5b89\u5168\u4e0e\u9690\u79c1\u3011\n");
        sb.append("\u00b7 Cookie \u4ec5\u4fdd\u5b58\u5728\u672c\u673a\u6a21\u5757\u79c1\u6709\u5b58\u50a8\uff1b\u6a21\u5757\u4e0d\u4e0a\u4f20\u3001\u4e0d\u8bb0\u5f55\u4ee4\u724c\u503c"
                + "\uff08\u754c\u9762\u4ec5\u663e\u793a\u952e\u540d/\u957f\u5ea6\uff0c\u4f53\u68c0\u7ed3\u679c\u5df2\u8131\u654f\uff09\u3002\u8bf7\u4e0d\u8981\u5c06 Cookie \u5206\u4eab\u7ed9\u4ed6\u4eba\u3002\n\n");
        sb.append("\u3010\u5176\u4ed6\u9650\u5236\u3011\n");
        sb.append("\u00b7 \u672c\u6a21\u5757\u4e3a\u300c\u65e0\u5c5e\u6027\u4f2a\u88c5\u300d\u6a21\u5f0f\uff1a\u4e0d\u4fee\u6539/\u4e0d\u4f2a\u88c5\u4efb\u4f55\u7cfb\u7edf\u5c5e\u6027\uff0c"
                + "\u4e5f\u4e0d hook SystemProperties\u3002\u95e8\u7981\u89e3\u9501\u76f4\u63a5 hook App \u7684 Java \u5224\u5b9a\u65b9\u6cd5\u8fd4\u56de true\u3002\n");
        sb.append("\u00b7 \u4f5c\u7528\u57df\uff1acom.miui.mediaeditor\uff08\u5fc5\u9009\uff09\uff1b\u76f8\u673a\u300c\u5f95\u5361\u4e00\u77ac\u300d\u9700\u52fe\u9009 com.android.camera "
                + "\u5e76\u4f7f\u7528 Magisk CameraOnly \u53d8\u4f53\uff08\u4ec5\u53ea\u8bfb\u6302\u8f7d\u56fd\u884c MiuiCamera.apk\uff09\u3002\n");
        sb.append("\u00b7 \u76f8\u518c\u4e3a Flutter+Rust\uff0cRust \u5c42\u4f1a\u7f13\u5b58\u65e7\u80fd\u529b\u7ed3\u679c\uff0c\u9996\u6b21\u542f\u7528\u540e\u9700\u6e05\u9664\u76f8\u518c\u7f13\u5b58/\u6570\u636e\u4e00\u6b21\u3002\n");
        sb.append("\u00b7 \u76f8\u673a\u5382\u5546 HAL \u7684 legendary \u80fd\u529b\u4e0e\u8fd0\u884c\u65f6 legend_mode SDK \u65e0\u6cd5\u7531 Java hook \u6dfb\u52a0\uff1b"
                + "\u82e5 HAL \u4e0d\u652f\u6301\uff0c\u5165\u53e3\u53ef\u80fd\u51fa\u73b0\u4f46\u5b9e\u9645\u6548\u679c\u7f3a\u5931\u3002\n");
        sb.append("\u00b7 CTA \u9690\u79c1\u534f\u8bae\u4e3a App \u6b63\u5e38\u5f39\u7a97\uff0c\u672c\u6a21\u5757\u6709\u610f\u4e0d\u5f3a\u5236\u3002\n");
        sb.append("\u00b7 \u672c\u9879\u76ee\u4e3a\u79bb\u7ebf\u9006\u5411\u7814\u7a76\u7528\u9014\uff0c\u672a\u5728\u771f\u673a\u6d4b\u8bd5\uff0c\u8bf7\u81ea\u884c\u627f\u62c5\u98ce\u9669\u3002\n");
        return sb.toString();
    }
}
