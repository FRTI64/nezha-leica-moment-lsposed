# LSPosed hook targets — Xiaomi-account Cookie injection + 徕卡一瞬「二次增强」gating

**Scope:** `com.miui.mediaeditor` CN **2.10.43.0.6** (vc 210994306) and GLOBAL **2.10.40.4.8-global** (vc 210994048), Xiaomi 17 Ultra (nezha / nezha_global).
**Artifacts:** CN smali `work/leica/cn/decomp/editor_smali_d1/`, `editor_smali_d2/`; global smali (baksmali'd by this task) `work/leica/lsposed/analysis/global_smali/{d1,d2,d3}/`; key evidence copies in `work/leica/lsposed/analysis/evidence/{cn,global}/`; decoded CN XOR strings `evidence/cn/decoded_strings.txt`.
**Machine-readable:** `work/leica/lsposed/analysis/hook_targets.json`.
**Obfuscation:** R8 renames support classes; `com.miui.mediaeditor.*`, `com.miui.gallery.*` package/class paths are NOT renamed and are identical CN/global. CN string obfuscation decoder `Lbw/b;->a` XOR 0x3091bc58 (char truncation ⇒ key 0xbc58). Global uses its own decoder (different key, e.g. `Lxz/b`); always prefer string-constant search across global smali to relocate a global class.

> **Recommended strategy: hook the STS-cookie *provider* only, hook NO CTA.**
> The CTA path is a normal user-consented dialog shown by `LegendaryActivity`; forcing it is both unnecessary and a privacy violation (see §2). The only extra hook needed on a **logged-out** device is the AccountUtil login probe used by the image-upload step (§3).

---

## 0. TL;DR hook table

| # | Purpose | CN 2.10.43.0.6 | GLOBAL 2.10.40.4.8 | Notes |
|---|---|---|---|---|
| 1 | **Cookie string provider** (user-obtained STS cookie) | `Lgo/e;->b()Ljava/lang/String;` | `Lbs/e;->b()Ljava/lang/String;` | Returns full STS cookie string (`key=value; key2=value2`). Single best hook point; also satisfies many direct callers. |
| 2 | Cookie OkHttp interceptor (evidence/context, no hook needed) | `Lnm/f;->a(Lvx0/f;)Lqx0/e0;` | `Ljq/g;->a(Lg11/g;)Lb11/e0;` | Adds header `Cookie: <go/e.b()|bs/e.b()>` on every cloud request. |
| 3 | Interceptor registration (context) | builder `Lnm/g;` field `h:Lnm/f;`, lazy lambda `Lmf0/p` → interceptor list into `Lir/h$a` | builder `Ljq/h;` field `h:Ljq/g;`, lazy lambda `Ld30/d` → interceptor list into `Lev/h$a` | Shows how the cookie reaches the chain; no hook needed. |
| 4 | **Login/account probe** (only if device has no Xiaomi account) | `Lga0/e;->a(Landroid/content/Context;)Ljava/lang/String;` → return user account name | `Lbe0/e;->a(Landroid/content/Context;)Ljava/lang/String;` | Upload hard-fails `uploadFile: failed, not login` when null/blank. Hook to return e.g. the account name that owns the user cookie, or trigger normal login `c(...)`. |
| 5 | **CTA gate — DO NOT FORCE** (optional nuclear option) | `Lcom/miui/gallery/preference/BaseGalleryPreferences$a;->a()Z` → `true` | same descriptor | Only disables the privacy dialog; NOT needed: the dialog is shown and can be accepted normally. Privacy-sensitive. |
| 6 | CTA callback (context) | `Llv/k;->l0(Z)V` | `Lhz/t;->k0(Z)V` | Called by CTA helper with `true` after user agrees; then `onXmLegendCloudEntryReady`. |
| 7 | Cloud-config gate | **none** in legend path (see §4) | **none** | `mediaeditor_filter_config_v2` is photo-filter only. |
| 8 | Signature generator (context; no hook needed) | `Lgo/d;->a(Lqx0/c0;Ljava/lang/String;J)Ljava/lang/String;` | `Lbs/d;->a(Lb11/c0;Ljava/lang/String;J)Ljava/lang/String;` | MD5(body)+`&appSecret=`+static secret+`&timeStamp=`+secs. Independent of account/cookie ⇒ cookie injection keeps signature valid. |
| 9 | Legacy availability predicates (context; props, not account) | `Lrt/a;->g()Z && Lah0/b;->g()Z`; `Lft/s;->a()Z` | `Lmx/a;->g()Z && Lr21/e;->b()Z`; `Lbx/t;->a()Z` | Already known from prior analysis (docs/cn_analysis.md §2.2, docs/global_analysis.md §4.1). |
| 10 | Manual-test provider entry | `com.miui.mediaeditor.api` / `MediaEditorProviderForGallery.call` (§6) | same | adb `content call` caveat: provider permission. |

Hook call shapes (LSPosed `XposedHelpers.findAndHookMethod`):

```java
// 1. Cookie provider — CN
XposedHelpers.findAndHookMethod("go.e", lpparam.classLoader, "b",
    new XC_MethodReplacement() {
      @Override protected Object replaceHookedMethod(MethodHookParam p) { return cfg.cookie; /* full "k=v; k2=v2" */ }
    });
// 1. Cookie provider — GLOBAL
XposedHelpers.findAndHookMethod("bs.e", lpparam.classLoader, "b", /* same */);
// 4. Account probe — CN / GLOBAL (only logged-out devices)
XposedHelpers.findAndHookMethod("ga0.e", lpparam.classLoader, "a", android.content.Context.class, ...);
XposedHelpers.findAndHookMethod("be0.e", lpparam.classLoader, "a", android.content.Context.class, ...);
```

For all hooks prefer **`findClass` by descriptor string** `Lgo/e;` etc. (obfuscated names; never hard-code a specific method index). Both target versions were found by string→class mapping, so name drift between minor versions is possible — module should resolve classes by *string-constant ownership* fallback (search classes whose method references `"getStsCookies failed, skip cookie: "`).

---

## 1. COOKIE hook — exact targets and semantics

### 1.1 CN 2.10.43.0.6

**Provider:** `Lgo/e;->b()Ljava/lang/String;` (`work/leica/cn/decomp/editor_smali_d1/go/e.smali:120-375`)

Decoded strings (XOR key 0xbc58, `evidence/cn/decoded_strings.txt`):

| smali line | decoded string | role |
|---|---|---|
| 18 | `StsCookieUtils` | log tag |
| 22 | `com.xiaomi` | AccountManager account type |
| 26 | `hyperos_sec_common` | authTokenType passed to `peekAuthToken`/`getAuthToken` |
| 67 | `getAccountsByType(...)` | Kotlin call-check |
| 129 | `serviceId` | (decoded, log/call-check; not used for token request) |
| 180 | `no xiaomi account found` | log when `getAccountsByType("com.xiaomi").length == 0` |
| 239 | `authtoken` | Bundle key checked for emptiness |
| 259 | `getAuthToken returned empty` | log |
| 270 | `stsCookies` | **Bundle key returned** |

Semantics of `Lgo/e;`:
- static volatile fields `b` (cached cookie string) / `c` (cached account name); `a()` = current first Xiaomi account name via `AccountManager.getAccountsByType("com.xiaomi")` (returns null on error/logged-out), `b()` = refresh STS cookie:

```smali
# go/e.smali:120-375 (condensed)
sput-object 0, Lgo/e;->b; sput-object 0, Lgo/e;->c          # invalidate cache
AccountManager am = AccountManager.get(app);                # app from Lnm/c;->b()Landroid/app/Application;
Account[] acc = am.getAccountsByType(decode("\u..."))       # -> "com.xiaomi"
if (acc.length == 0) { log(tag,"no xiaomi account found"); return null; }
token = am.peekAuthToken(acc[0], "hyperos_sec_common");
if (!TextUtils.isEmpty(token)) am.invalidateAuthToken("com.xiaomi", token);
Bundle b = am.getAuthToken(acc[0],"hyperos_sec_common",null,null,null,null).getResult();
String at = b.getString("authtoken");                        # empty -> log + return null
String cookies = b.getString("stsCookies");                  # RETURN value
if (cookies != null && !cookies.isEmpty()) { go/e.b = cookies; go/e.c = go/e.a(); }
```

So `b()` = **"refresh STS cookies via Xiaomi AccountManager auth token and return the `stsCookies` bundle string"**. It is the only source of the Cookie header content. `go/e.c` caches the *account name* associated with the cookie; the interceptor only uses `c` to detect account switch (see 1.3) — it never compares the returned cookie against a stored/expected value. ⇒ **Hooking `b()` to return a user-obtained cookie string is sufficient for the Cookie header path.**

**Interceptor:** `Lnm/f;->a(Lvx0/f;)Lqx0/e0;` implements `Lqx0/u;` (OkHttp Interceptor) (`evidence/cn/nm_f_interceptor.smali`). Decoded log strings: `serviceId`(line17, log tag), `StsCookieUtils`(49), `account changed, invalidate cookie cache`(55). Logic:

```smali
Object cookie;
if (Lgo/e;->b != null && Lgo/e;->b.length() != 0) {
    if (Objects.equals(Lgo/e;->a(), Lgo/e;->c)) cookie = Lgo/e;->b;   // cache hit (same account)
    else { log("StsCookieUtils","account changed, invalidate cookie cache");
           Lgo/e;->b = null; Lgo/e;->c = null; cookie = Lgo/e;->b(); } // refresh
} else cookie = Lgo/e;->b();                                         // refresh
if (cookie != null && cookie.length() != 0)
    chain.mutate().header("Cookie", cookie);                          // "Cookie" literal, smali:93
chain.mutate().build() -> Chain.proceed(...)
```
⚠ Because the interceptor only calls `b()` when the static cache is empty/mismatched, a hook on `b()` is still hit on first request of each process and after account changes (`a()` returns null when logged out vs `c` null → `Objects.equals(null,null)` is true! → on a logged-out device with a stale cache it may take the cache path; cache is process-local and empty on first request, so it refreshes once and then caches the hook value; account-change comparison `a()==c` becomes `null vs <cookie-value?>` — c is account name from `a()`, which is null when logged out; after first refresh c = a() = null, so cached hook cookie is reused. Fine.)

**Registration into the OkHttp chain:** cookie interceptor is held in the cloud-SDK client builder `Lnm/g;` field `h:Lnm/f;` (`nm/g.smali:99-103`), and installed through lazy lambda `Lmf0/p` (`evidence/cn/mf0_p_registration.smali:47-85`) as a `List` (single-element) passed to the Retrofit-like builder `Lir/h$a` (`invoke-static Lah0/h;->d(Object)` ⇒ `List`). All cloud API managers (LegendCloudManager, AiAvatarCloudManagerV2, EditSuggestCloudManager, SegmentCloudManager, …) build their clients from this `Lnm/g`/`Lnm/b` (base `Lnm/b;->b`, cloud env `Lnm/c`) ⇒ one interceptor instance covers every `api.open.ai.xiaomi.com` call. `Lnm/c;->a(...)` / `AIConfig` (`cloud_ai_config`) can override base URL from a remote config; not relevant to cookie.

**Direct callers of `go/e.b()` (bonus):** besides the interceptor, cloud managers call the provider directly (e.g. `LegendCloudManager.smali` lines 1271-1275, 2105-2109, 3300-3304, 4447-4451, 5191-5195) — hooking `b()` covers those too.

### 1.2 GLOBAL 2.10.40.4.8-global

Class mapping (found by string-constant ownership in `global_smali/`):
- `"getStsCookies failed, skip cookie: "` / `"refreshStsCookies cost: "` ⇒ `Lbs/e;->b()Ljava/lang/String;` (`evidence/global/bs_e_sts_cookie.smali`) — **same code, same account type `com.xiaomi`, same authTokenType `hyperos_sec_common`, same bundle keys `authtoken`/`stsCookies`, same static fields `a/b/c`** (`a()` at line 39, `b()` at line 120, clinit line 15 — identical structure to CN `go/e`).
- Cookie interceptor: `Ljq/g;->a(Lg11/g;)Lb11/e0;` — synthetic final, implements OkHttp `Interceptor` equivalent; line 13 `Lbs/e;->a`, line 21 `Lbs/e;->b`, line 34 `Lbs/e;->a()`, line 38 `Lbs/e;->c`, line 65/67 invalidate cache, line 71 `invoke-static Lbs/e;->b()`, **line 93 `const-string "Cookie"`** ⇒ exact global analog of CN `Lnm/f` (`evidence/global/jq_g_cookie_interceptor.smali`).
- Registration: builder `Ljq/h;` field `h:Ljq/g;` (`jq_h_builder.smali:39,99-103`); lazy lambda `Ld30/d` reads `Ljq/h;->h` → `List` → `Lev/h$a` (`evidence/global/d30_d_registration.smali:61-89`).
- Hook: `Lbs/e;->b()Ljava/lang/String;` returns user cookie.

### 1.3 Sufficiency analysis

- Returned value is used **verbatim** as the `Cookie` header. No format validation, no compare against `b`/`c` payload, no signature check on the cookie in the client.
- Only cache-invalidation path compares `go/e.a()` (account *name*) with `go/e.c`; on a logged-out device both may be null ⇒ cached value reused (fine).
- Other client-side consumers of the cookie exist (401-retry logic in `LegendCloudManager`: log `cookie 鉴权失败(401)，刷新 cookie 重试。Sign Info：`): on 401 it likely calls `go/e.b()` again — hook still returns the user cookie, so retry behaves consistently.
- Server-side: cookie is the account identity; the create body carries no accountId (see §3), so **account identity is derived by the server from this Cookie header** ⇒ a valid user STS cookie is the whole authentication. Risk: server may reject a cookie whose account has no CN entitlement (server-side quota/points).

---

## 2. CTA gate — finding, decision, privacy note

### 2.1 Where CTA appears

- Provider `MediaEditorProviderForGallery.call(String,String,Bundle)` result bundle includes **`key_is_cta_available` = `Lcom/miui/gallery/preference/BaseGalleryPreferences$a;->a()Z`** for `method_request_function_list_with_cta` (CN/global identical: `provider...smali:2072-2082`, log `isCTAAvailable: `). This is *informational for the gallery UI* — the legend booleans `method_is_legend_available` / `method_is_xm_legend_available` returned in the same function list are computed with **no CTA and no login input** (CN `provider...smali:2610-2640`; global `provider...smali:2610-2640`, same).
- Job/UI-time CTA check lives in `Lcom/miui/mediaeditor/legendary/c` (same descriptor both versions; CN method `x(Ljava/lang/Object;)Ljava/lang/Object;` line ~99, global method `t(...)`; coroutine state machine), which runs:
  1. `BaseGalleryPreferences$a;->a()Z` (CTA allowed?) — if **false**: show CTA dialog via `Ltd/j;->b(Landroidx/fragment/app/x;Lvd/b;)V` (CN) / `Lph/j;->b(Landroidx/fragment/app/w;Lrh/a;)V` (global), guarded by `LegendaryActivity.m` so only one dialog; callback = `Llv/k;->l0(Z)V` (CN) / `Lhz/t;->k0(Z)V` (global).
  2. Callback: `true` ⇒ log `"checkXmLegendCloudEntryIfNeeded: CTA agreed, check login"` then `LegendaryActivity.r0(onXmLegendCloudEntryReady, finishXmLegendCloudEntry)`; `false` ⇒ log `"checkXmLegendCloudEntryIfNeeded: CTA not allowed"` + `LegendaryActivity.w0()` (abort).
  3. If CTA already agreed ⇒ skips dialog, calls `onXmLegendCloudEntryReady` directly. Log `"checkXmLegendCloudEntryIfNeeded: CTA agreement already showing, skip"` covers re-entry.
- `BaseGalleryPreferences$a;->a()Z`: shared-prefs `can_connect_network` + `BaseGalleryPreferences$a.a` override; `b(Z)` writes CTA state and broadcasts `com.miui.gallery.action.CTA_CHANGED`; receiver `BaseGalleryPreferences$b` logs `onReceive CTA agreed`. CN evidence `evidence/cn/BaseGalleryPreferences_a_cta.smali`; **global identical descriptor** (`global_smali/d1/com/miui/gallery/preference/BaseGalleryPreferences$a.smali`).

### 2.2 Decision: **do NOT hook CTA**

- The 二次增强 entry visibility depends only on the two predicates (§0 #9) — no CTA requirement. Once props are set, the entry is offered.
- When the user opens the legendary flow, if CTA was never accepted, the **privacy dialog is displayed by the app itself** and, on accept, the normal callback continues (`CTA agreed, check login`). So CTA can be accepted normally — nothing to bypass for the feature to work.
- Forcing CTA (`BaseGalleryPreferences$a;->a()Z` → always `true`) would: (a) suppress a mandatory privacy-agreement dialog, (b) be a legal/privacy violation of the user's explicit consent, (c) potentially break `Lid0/a` CloudConfig init, which is CTA-gated: `"CTA not permitted yet, CloudConfig init deferred (ctaOverride=)"` (global `evidence/global/id0_a_cloudconfig_init.smali:374`) and CN analog `Ln90/a`. **Module must only enable the entry; the user must accept the CTA dialog themselves.**
- Optional-but-not-recommended nuclear fallback if a future ROM refuses to show the dialog: hook `Lcom/miui/gallery/preference/BaseGalleryPreferences$a;->a()Z` (static, both versions) to return `true`, or hook `Llv/k.l0`/`Lhz/t.k0` to pass `true`. Document this only as debug/off-by-default.

---

## 3. LOGIN / ACCOUNT requirements (separate from cookie path)

**Create-task path does NOT call AccountManager and does not check login.** CN `LegendCloudManager.createAITask` builds `CreateLegendV2TaskParams` as
`(String imgUrl, String oaid, String androidId, String deviceName, int isLCC, Float aperture, Integer focusX, Integer focusY …)` — from `CloudLegendV2Param` getters, `nm/e.b(appContext)` (androidId), `Build.DEVICE`, `rt/a.g()` (isLCC). **No accountId / accountName field**; identity comes from the Cookie header only (`evidence` cn LegendCloudManager lines 90-190). CN entity/JSON fields: `androidId, aperture, deviceName, deviceType, focusX, focusY, imgUrl, oaid, requestId` (cn_analysis §2.3). `requestId`/`oaid` are passed in by `CloudLegendV2Param` constructed in `tv/h0.smali:544` / `tv/x.smali:205` (CN) — exact OAID provider class **NOT DONE**.

**But the upload step hard-checks login:** CN `yn/u.smali` (`CloudLegendV2UploadProcessor.e(...)`, line ~72-150) calls
`Lga0/e;->a(Landroid/content/Context;)Ljava/lang/String;` (`AccountUtil`); if null or blank ⇒ `"uploadFile: failed, not login"` (line 343) and returns `EdgeCloudResult$Error`. The same pattern exists in `yn/j.smali` (`uploadFile: failed, not login`, line 326) and in global smali (global AccountUtil = `Lbe0/e`, same methods `a/b/c`; global upload processor class name differs — search global smali for `uploadFile: failed, not login` if needed).
- `AccountUtil.a(Context)` returns `AccountManager.getAccountsByType("com.xiaomi")[0].name`; null if none. In the "international" branch (`Lgz0/b;->b` true) it first tries `com.xiaomi.mihomemanager` when `sharedUserId == "android.uid.system"`, and returns literal `"demo"` in that case (CN `ga0/e.smali:30-100`; global `be0/e.smali:23-148`).
- `AccountUtil.b(Context)` derives a numeric id from the account name (`parseInt(chars) << 2`) — used by other cloud flows; legend create path uses `nm/e.b` for androidId instead.
- `AccountUtil.c(activity, continuation)` = **normal Xiaomi login flow** (`AccountManager.addAccount("com.xiaomi","passportapi",null,null,activity,...)`, global `be0/e;->c(Landroidx/fragment/app/w;Lct0/l;)V`, CN `ga0/e;->c(Landroidx/fragment/app/x;Ltp0/l;)V`).

**Consequences for module:**
1. Device with a Xiaomi account signed in: **no account hook needed**; cookie hook suffices (upload login probe passes).
2. Device logged out + user-supplied cookie: upload fails `not login` *before* create ⇒ module must additionally hook `Lga0/e;->a(Landroid/content/Context;)Ljava/lang/String;` (CN) / `Lbe0/e;->a(...)` (GLOBAL) to return a non-empty account name (e.g. the user's account name matching the cookie), least-invasive. (`go/e.a()` is only used by the cookie interceptor cache; hooking it is not required.)
3. `oaid` source / `requestId` remains NOT DONE (passed through `CloudLegendV2Param`; not needed for hook design).

---

## 4. Cloud-config gate — confirmed absent from the client legend path

- Prior analysis: the two provider predicates contain no cloud-config/account input (`docs/cn_analysis.md` §2.2, `docs/global_analysis.md` §4.1) — reconfirmed here: CN provider legend branch (`evidence/cn/provider_legend_branch_excerpt.smali`) = `Build.DEVICE=="nezha" && ro.theme_customize=="LCC" && region=="cn"` properties only.
- **No class under `com.miui.mediaeditor.legendary.*` or `com.miui.mediaeditor.aigc.cloud.*` references `com/miui/camerainfra/cloudconfig`** (grep over full CN smali: zero hits in legend/cloud packages).
- `mediaeditor_filter_config_v2` appears only in **photo-filter / vlog** code: CN `editor_smali_d2/{bv/e,bv/f,bv/g,tz/h,tz/j,vz/i,we0/f,ze0/j1$b}.smali` (+ `com/miui/gallery/vlog/.../rule/c.smali`) — i.e. server-config for the *filter* marketplace, not the legend job. **No client-side legend cloud-config allowlist exists to hook.**
- `camerainfra CloudConfig` init is deferred until CTA: CN `Ln90/a`, global `Lid0/a` (`"CTA not permitted yet, CloudConfig init deferred (ctaOverride=)"`), but it is not consulted by the legend create path.
- Support-device lists (`leica_support_devices`, `lcc_support_devices`, `mist_support_devices` in `supportDeviceList`) are **server-delivered** rule data (`Lri0/g.a` global parser, CN `tz/*`/`bv/*` family); they are evaluated server-side for the job ⇒ cannot be bypassed client-side. Risk note: even with cookie + props, server may refuse the create for non-CN account/SKU.

---

## 5. `signature` + `timeStamp` query params — generator and cookie-independence

**Generator (CN):** `Lgo/d;->a(Lqx0/c0;Ljava/lang/String;J)Ljava/lang/String;` (`evidence/cn/go_d_signature.smali`), called from the cloud-SDK request builder `Lbm/g` before dispatch (`bm/g.smali:262-272`, full call chain up from `Lnm/g`):
```smali
v16 = System.currentTimeMillis(); ts = v16 / 1000              # seconds
body = RequestBody.create(jsonString, MediaType JSON)
appSecret = Lnm/e;->a()Ljava/lang/String()                     # -> Lnm/a;->b static
signature = Lgo/d;->a(body, appSecret, ts)                     # log tag "NetUtils"
# then signature appended as query param `signature`; ts as query `timeStamp`
```
`go/d.a` algorithm (smali lines 20-140): `bodyHash = MD5(bodyBytes)` → hex; then `MD5( bodyHash + "&appSecret=" + appSecret + "&timeStamp=" + ts )` → hex `sign`; logs `generateCreateSign bodyHash: <hash>, sign: <sign>` (tag `NetUtils`).
- `appSecret` = **static bundled constant**, NOT account/cookie-derived: `Lnm/a;->b` is set in `<clinit>` by hashing a build-type string (`release` / `TESTRelease` / `debug`) and picking one of three XOR-decoded UUIDs (`evidence/cn/decoded_strings.txt`): `0a9dcc27-c344-4a12-83dd-167134573c97`, `c2b9bba8-3a9e-4f49-8ad2-3cb0c1cb8eca`, `ceb5a5f0-880c-4356-929d-842e243ddd47`.
- `timeStamp` = seconds since epoch, computed at request build time (same value added to query and hashed).

**GLOBAL:** `Lbs/d;->a(Lb11/c0;Ljava/lang/String;J)Ljava/lang/String;` (`evidence/global/bs_d_signature.smali`) — byte-identical algorithm (`appSecret`, `&appSecret=`, `&timeStamp=`, `generateCreateSign bodyHash: ` log tag, MD5). Called by global `LegendCloudManager` for each request family (lines 616, 1424, 2993, 4140, 4886). Global appSecret: env/app-key classes `Lsq/c` (uses `Ljq/c;->b`) and `Lsr/b` (literal `appSecret=yJ0eG6jK1kE3wA3yV8aQ8cE3xE8zA4wY&app_type=`) — region/build-tied static constants (exact global legendV2 appSecret selection NOT DONE, but call-site arg is a constant, not account state).

**Risk verdict:** the signature depends only on (request JSON body, bundled appSecret, current timestamp). It does **not** depend on the Xiaomi account or cookie ⇒ **hooking `go/e.b()`/`bs/e.b()` does not invalidate the signature**; no signature hook needed. Residual risk: the CN vs global APK may ship different `appSecret`s bound to different server channels; if the CN server rejects the global appSecret the module would need a signature hook (or CN APK) — that is a server/region question, not caused by cookie injection. Conversely the signature does not help spoof the account: identity is the cookie (server-side).

---

## 6. Optional / manual test

- **Provider:** manifest CN `editor_res/AndroidManifest.xml:155`:
  `<provider android:name="com.miui.mediaeditor.provider.MediaEditorProviderForGallery" android:authorities="com.miui.mediaeditor.api" android:exported="true" android:permission="com.miui.mediaeditor.api.PROVIDER"/>` (same authority/global). `call(String method, String arg, Bundle extras)` dispatches: `method_is_legend_available`, `method_is_xm_legend_available`, `method_request_function_list_with_cta` (returns `key_function_list` + `key_is_cta_available`), `key_common_is_available`, etc. (`evidence/{cn,global}/provider_full.smali`; CN legend reply bundle keys at cn provider ~2610-2640: `method_is_legend_available`, `method_is_xm_legend_available` booleans).
  adb probe (⚠ provider has signature/system permission `com.miui.mediaeditor.api.PROVIDER`; plain `adb shell` UID usually lacks it — use only on debuggable/root shell, else this returns SecurityException):
  ```
  adb shell content call --uri content://com.miui.mediaeditor.api --method method_is_legend_available
  adb shell content call --uri content://com.miui.mediaeditor.api --method method_is_xm_legend_available
  ```
- **LegendaryActivity:** manifest CN `editor_res/AndroidManifest.xml:260` `com.miui.mediaeditor.legendary.LegendaryActivity` exported=true, singleTop, portrait. Intent extras read in `A0(Landroid/content/Intent;)` (`cn editor_smali_d2/.../LegendaryActivity.smali` ~lines 486-730): `legend_status_type` (String state: `loading/reset/error/queued/cancel/success`), `legend_call_from_notification` (boolean), `editor_support_overwrite` (boolean), `legend_progress`, `legend_error_code`, `legend_error_message`, `legend_result_uri` (Parcelable), `legendaryBaseFragment` (fragment tag). Action string `com.miui.mediaeditor.action.legendary`, internal route `/legendary/service` (prior `docs/cn_analysis.md` §2.1). **Primary image-URI extra key not extracted (NOT DONE)** — read `A0` further if README needs a launch command; simplest manual test = gallery → 一瞬 photo → 二次增强.
- **Cookie test without account:** set cookie via module config; expect cloud request header `Cookie: …` in logcat (Xposed log hook on `Lnm/f`/`Ljq/g` header) and no `getStsCookies failed`/`no xiaomi account found` from the app's own path (our hook bypasses it).

---

## 7. Evidence index (this task's files)

| Path | Contents |
|---|---|
| `work/leica/lsposed/analysis/evidence/cn/nm_f_interceptor.smali` | CN cookie interceptor `Lnm/f` (Cookie header, cache logic) |
| `…/evidence/cn/go_e_sts_cookie.smali` | CN `Lgo/e` STS cookie provider (a/b methods, decoded comments in file + decoded_strings.txt) |
| `…/evidence/cn/nm_g_builder.smali`, `mf0_p_registration.smali` | CN interceptor holder + lazy registration into `Lir/h$a` |
| `…/evidence/cn/nm_c_cloudenv.smali`, `nm_e_deviceid.smali` | cloud env (`AigcCloud`, base URL, `cloud_ai_config`), androidId helper |
| `…/evidence/cn/go_d_signature.smali`, `nm_a_appsecret.smali` | CN signature generator + appSecret selection |
| `…/evidence/cn/ga0_e_accountutil.smali`, `yn_u_upload_logincheck.smali` | AccountUtil (account name/login flow) + upload `not login` hard check |
| `…/evidence/cn/lv_k_cta_callback.smali`, `legendary_c_cta_flow.smali`, `BaseGalleryPreferences_a_cta.smali` | CN CTA callbacks/flow/preference |
| `…/evidence/cn/provider_full.smali`, `provider_legend_branch_excerpt.smali` | CN provider dispatch + legend/CTA keys |
| `…/evidence/cn/decoded_strings.txt` | XOR-decoded strings (go/e, nm/f, nm/a, nm/c) = proof of CN semantics |
| `…/evidence/global/bs_e_sts_cookie.smali` | GLOBAL STS cookie provider (same semantics) |
| `…/evidence/global/jq_g_cookie_interceptor.smali` | GLOBAL cookie interceptor (Cookie header line 93) |
| `…/evidence/global/jq_h_builder.smali`, `d30_d_registration.smali` | GLOBAL builder + registration into `Lev/h$a` |
| `…/evidence/global/bs_d_signature.smali` | GLOBAL signature generator |
| `…/evidence/global/be0_e_accountutil.smali`, `hz_t_cta_callback.smali` | GLOBAL AccountUtil (+ login `c()`) and CTA callback |
| `…/evidence/global/id0_a_cloudconfig_init.smali` | GLOBAL camerainfra CloudConfig init CTA gating |
| `…/evidence/global/legendary_c_cta_flow.smali`, `provider_full.smali` | GLOBAL CTA flow + provider |
| `work/leica/lsposed/analysis/global_smali/` | baksmali of global `classes{1,2,3}.dex` (176 MB; raw dex deleted) |
| `work/leica/lsposed/analysis/global_string_xref.tsv` | string→class/method xref scan of global APK (used to map CN names → global) |

**NOT DONE / partial:** (a) global upload-processor class name for the `not login` check (CN `yn/u`, `yn/j`); (b) OAID/requestId provider (`CloudLegendV2Param` construction sites `tv/h0`, `tv/x` CN) — values pass through, not account-bound; (c) exact global appSecret constant used for legendV2 (call-site arg is static; `sq/c`/`sr/b` candidates); (d) LegendaryActivity primary image-URI intent extra key (extras listed, URI key needs another pass of `A0`); (e) end-to-end server acceptance of injected cookie is untestable offline.
