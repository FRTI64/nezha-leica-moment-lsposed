# LSPosed 模块：小米账号 Cookie 登录/注入 + 相册编辑「徕卡一瞬 · 二次增强」门禁解锁

> 交付物：`out/leica_moment_port/lsposed/LeicaMoment_LSPosed_v1.1.0.apk`（工作区源文件 `work/leica/lsposed/dist/LeicaMoment_LSPosed_v1.1.0.apk`；legacy Xposed 模块，LSPosed 可用）
> 目标应用：`com.miui.mediaeditor`（相册-编辑；国际版 2.10.40.4.8-global / 国行 2.10.43.0.6）
> 目标设备：Xiaomi 17 Ultra（`nezha` / `nezha_global`），HyperOS 4 / Android 17
> 本模块基于对两国 ROM 的静态逆向（`docs/ANALYSIS.md`、`docs/lsposed_hook_targets.md`），**未在真机上运行验证**（本环境无设备）。

---

## 1. 模块能做什么

| 能力 | 实现方式 | 默认 |
|---|---|---|
| **绕过相册编辑的「徕卡一瞬」入口门禁**（`method_is_legend_available`） | 在 `com.miui.mediaeditor` 进程内 hook `android.os.SystemProperties.get/getBoolean/getInt`，伪装 `ro.miui.build.region=cn`、`ro.theme_customize=LCC`、`ro.theme_leica=true`、`ro.boot.product.theme_customize=P1_Leica`、`ro.special_edition=true`；**不修改系统属性文件**，只影响该进程 | ✅ 开 |
| **注入小米账号 Cookie**（云端请求的 `Cookie` 头） | hook 混淆类 `go.e.b()`（CN）/ `bs.e.b()`（GLOBAL）——App 原生 STS Cookie 提供者；用用户配置的 Cookie 替换返回值，并同步写入其进程内静态缓存字段（否则只有第一个请求生效） | ✅ 开 |
| **支持登录小米账号** | ① 模块 UI 内 WebView 登录并抓取 Cookie；② 一键调起**系统小米账号登录**（`AccountManager.addAccount("com.xiaomi","passportapi")`）；③ 已登录时由 App 原生链路取 STS Cookie 并自动上报给模块 UI（可回填复用） | UI 功能 |
| **未登录设备的登录探测兜底** | 云端**上传步骤**有硬检查 `uploadFile: failed, not login`（`AccountUtil`），hook `ga0.e.a(Context)`（CN）/ `be0.e.a(Context)`（GLOBAL）：无系统账号但已配置 Cookie 时返回从 Cookie 解析出的账号标识，避免上传前被拦 | ✅ 开（仅在无账号时生效） |
| **CTA 隐私协议** | **不强制**。分析确认：入口可见性只由 props 判定；CTA 对话框由 `LegendaryActivity` 正常弹出、用户亲自同意即可（强制该状态属于程序化代签隐私协议，默认关闭/不实现） | ❌ 不绕过 |
| **云端签名** | 无需 hook：`signature` 由 App 客户端用内置 `appSecret` + body MD5 + 时间戳计算，与账号/Cookie 无关，换 Cookie 不会破坏签名 | — |
| **云端权益（点数/白名单）** | **无法**客户端绕过（服务端校验，硬件认证 token + 服务端 CloudConfig allowlist）；模块不提供任何伪造/绕过 | — |

---

## 2. 组成与工作原理

模块 APK 包含 3 部分（单一 APK，既是可打开配置的 Android 应用，又是 Xposed 模块）：

```
com.nezha.leica.lsposed
├── MainActivity          模块 UI：WebView 登录抓取 / 手动粘贴 / 系统账号登录 / 捕获回填 / 开关
├── CookieConfigProvider  exported ContentProvider（无权限声明，但按 Binder UID+包名白名单校验调用方）：
│                         供被 hook 的 mediaeditor 进程读取配置 / 回传 App 实际取到的系统账号 Cookie
├── HookEntry             legacy Xposed 入口（assets/xposed_init；IXposedHookLoadPackage）
└── ModuleConfig          常量与 SharedPreferences 访问
```

数据流：
```
模块 UI（用户配置 Cookie / 开关）
      │  SharedPreferences（模块私有）
      ▼
CookieConfigProvider  ←──call("get_config")──  mediaeditor 进程内的 HookEntry（8s TTL 缓存）
      ▲                                              │
      └──call("set_captured_cookie")─────────────────┘（App 原生取到的 STS Cookie 回传）
                                                     │
HookEntry:  SystemProperties 伪装 → method_is_legend_available 为 true
            Cookie 提供者兜底   → 所有云端请求带用户 Cookie
            AccountUtil 兜底    → 未登录设备也能通过上传前登录检查
```

LSPosed 特性/兼容性要点：
- 使用 **legacy Xposed API**（`de.robv.android.xposed`，API 82）；APK 内**不打包** API 类（仅编译期依赖），否则 LSPosed 会拒载。
- legacy 模块由 LSPosed 按 `AndroidManifest` 的 `xposedmodule` / `xposedminversion` + `assets/xposed_init` 识别；`META-INF/xposed/module.prop`、`scope.list` 为现代 API 元数据（保留但 legacy 模式下 scope 需在 LSPosed 管理器手动勾选）。
- 作用域：`com.miui.mediaeditor`（必选）；`com.miui.gallery`（可选，本版本不对其装方法级 hook）。

---

## 3. Hook 目标与证据（两国版本）

| # | 目的 | 国行 2.10.43.0.6 | 国际版 2.10.40.4.8-global | 证据 |
|---|---|---|---|---|
| 1 | 入口门禁判定（props 读取） | 判定链 `rt.a.g() && ah0.b.g()`，其中读 `ro.theme_customize`、`ro.miui.build.region` | 判定链 `mx.a.g() && r21.e.b()`，同 props | `docs/cn_analysis.md §2.2`、`docs/global_analysis.md §4.1` |
| 2 | **Cookie 提供者** | `Lgo/e;->b()Ljava/lang/String;` | `Lbs/e;->b()Ljava/lang/String;` | `lsposed_hook_targets.md §1`、`evidence/{cn,global}/*_sts_cookie.smali` |
| 3 | Cookie 拦截器（上下文，不 hook） | `Lnm/f;->a(...)`（加 `Cookie` 头） | `Ljq/g;->a(...)` | `evidence/cn/nm_f_interceptor.smali`、`evidence/global/jq_g_cookie_interceptor.smali` |
| 4 | **登录探测（未登录兜底）** | `Lga0/e;->a(Landroid/content/Context;)Ljava/lang/String;` | `Lbe0/e;->a(Landroid/content/Context;)Ljava/lang/String;` | `evidence/*/…accountutil.smali`、CN `yn/u.smali:156,343`（`uploadFile: failed, not login`） |
| 5 | 正常登录流程（UI 调起） | `AccountManager.addAccount("com.xiaomi","passportapi",…)`（App 内 `AccountUtil.c`） | 同 | `accountutil.smali` |
| 6 | 签名生成（无需 hook） | `Lgo/d;->a(...)`：MD5(body)+`&appSecret=`+内置 secret+`&timeStamp=` | `Lbs/d;->a(...)` | `evidence/*/…signature.smali` |
| 7 | CTA 状态（**不强制**） | `Lcom/miui/gallery/preference/BaseGalleryPreferences$a;->a()Z`（仅记录，不 hook） | 同 descriptor | `evidence/cn/BaseGalleryPreferences_a_cta.smali` |
| 8 | 云控 allowlist（无客户端门禁） | 已确认 legend 路径无 | 已确认 | `lsposed_hook_targets.md §4` |

### 3.1 为什么 hook Cookie 提供者就够了
`go/e.b()` / `bs/e.b()` 返回的字符串被拦截器**原样**用作 `Cookie:` 头，没有校验/对比（静态字段 `c` 只用于检测"账号是否切换"）。且 `LegendCloudManager` 等多个云管理器直接调用 `b()`，一处 hook 覆盖 create / getResult / upload 全链路。
该方法的原始逻辑：`AccountManager.getAccountsByType("com.xiaomi")` → `getAuthToken(account,"hyperos_sec_common")` → Bundle 取 `authtoken`（非空校验）→ 返回 `stsCookies`。
→ 因此：系统内已登录小米账号时，App 本来就能自己取到 cookie（此时模块的"注入"是可选的覆盖）；未登录时可用模块配置的 Cookie 顶替。

### 3.2 进程内 Cookie 缓存
拦截器 `nm/f`（CN）在字段 `go.e.b` 非空且账号未变时**直接使用静态缓存**、不再调用 `b()`。因此模块在 hook 到 `b()` 后还会把用户 Cookie 写入该静态字段（`setStaticObjectField(target,"b",cookie)`），只覆盖 "b" 不动 "c"（账号名），保证账号切换检测仍有效。
**修改 Cookie/开关后必须强制停止 `com.miui.mediaeditor`（建议连同 `com.miui.gallery`）再重开**，否则进程内缓存不刷新。

---

## 4. Cookie / 登录说明

- **STS Cookie（最可靠）**：App 通过系统账号管理器取得的 `stsCookies`（小米账号 authTokenType `hyperos_sec_common`）。设备已登录小米账号时，模块会让 App 正常取一次并把结果回传到模块 UI，用户可"回填"复用。
- **WebView 登录抓取**：模块 UI 内登录 `account.xiaomi.com` 后合并 `.xiaomi.com/.mi.com` 域 Cookie（`serviceToken/cUserId/userId/passToken` 等）。⚠️ 这与原生 STS Cookie **不一定等价**，云 API 可能只接受 STS Cookie；失败时请改用系统账号路径或手动粘贴已知可用 Cookie。
- **系统登录按钮**：`AccountManager.addAccount("com.xiaomi","passportapi",…)` 会调起系统小米账号登录界面（由小米账号 App 处理，凭据不经过本模块）。
- **手动粘贴**：支持 `name=value; name2=value2` 形式，适合从已登录设备/抓包复制。
- 未登录 + 仅 Cookie 时：模块的 AccountUtil hook 会为上传步骤提供账号标识兜底（从 Cookie 的 `userId/cUserId` 解析，否则用 `demo`），否则上传前会报 `uploadFile: failed, not login`。

---

## 5. 安装与使用

1. 安装 APK：`adb install -r LeicaMoment_LSPosed_v1.1.0.apk`（或文件管理器安装）。
2. LSPosed 管理器 → 模块 → 启用「徕卡一瞬 LSPosed」→ 勾选作用域 `com.miui.mediaeditor`（可选 `com.miui.gallery`）→ 重启手机一次。
3. 打开模块 App：登录/粘贴/捕获 Cookie → 打开需要的开关 → 保存。
4. **强制停止** `com.miui.mediaeditor`、`com.miui.gallery`，再打开「相册 → 编辑」验证入口。
5. 生效日志（LSPosed 管理器 → 日志，前缀 `LeicaMomentLSP:`）：
   ```
   handleLoadPackage: pkg=com.miui.mediaeditor ...
   hooked SystemProperties.get(String)
   cookie hook installed: go.e.b()Ljava/lang/String;  (CN) / bs.e.b()... (GLOBAL)
   injecting user cookie (N chars) via ...
   cookie cache field updated: go.e.b -> user cookie ...
   captured system-account cookie (N chars) reported to module app, ok=true
   account probe hook: returned derived account id (...)
   ```

完整用户指南见 `work/leica/lsposed/app/README_APP.md`（随 APK 一起交付）。

---

## 6. 安全 / 隐私 / 合规

- Cookie 只保存在模块 App 私有 SharedPreferences；只通过 exported Provider 提供给被 hook 的两个小米包（Provider 内按 `Binder.getCallingUid()` + `getPackagesForUid()` 白名单校验），其它应用无法读取。
- 系统账号登录走系统 Authenticator，模块不接触小米账号密码。
- **不伪造**硬件认证 token，**不绕过**服务端权益/计费/设备白名单，**不强制**通过 CTA 隐私协议（用户须自行同意）。
- 属性伪装仅限 `com.miui.mediaeditor` 进程，不修改系统属性文件，重启即恢复。

---

## 7. 验证状态

已静态验证（本环境无设备/无真机测试）：
- 构建可复现：`build.sh app dist/LeicaMoment_LSPosed_v1.1.0.apk` 两次构建 sha256 一致。
- 签名：apksigner `Verifies`；v1+v2+v3 方案齐全（`--min-sdk-version 21` 校验）。
- APK 内容：`classes.dex`、`assets/xposed_init`、`META-INF/xposed/{module.prop,scope.list}`；`aapt2 dump badging` 包名/版本/minSdk 29/targetSdk 35/INTERNET/launchable Activity 正确；dex 内**无**打包 `de.robv.android.xposed` API 类。
- Hook 目标：来自两国 smali 逐行证据（见 `docs/lsposed_hook_targets.md` 与 `analysis/evidence/`），类名/方法描述符均在模块代码中以"目标列表 + try/catch"形式实现，未知版本会安全跳过并记录日志。
- 模块 UI/Provider/脚本：代码走查 + dex 字符串校验。

**未验证**：真机运行时 hook 生效、云端任务最终是否被服务端接受（权益/硬件 token）、不同 mediaeditor 小版本类名漂移、LSPosed 在 Android 17 上的可用版本（上游 LSPosed 最后 release v1.9.2 仅支持到 Android 14，本项目设备需要维护分支/新版本，legacy 模块格式仍兼容）。

---

## 8. 文件索引

| 路径 | 说明 |
|---|---|
| `out/leica_moment_port/lsposed/LeicaMoment_LSPosed_v1.1.0.apk`（源：`work/leica/lsposed/dist/`） | 最终模块 APK（29,284 B，sha256 `8bf6c96e…a0f0f`） |
| `work/leica/lsposed/app/` | 模块源码（4 个 Java 文件 + manifest + xposed 元数据） |
| `work/leica/lsposed/app/README_APP.md` | 用户指南（中文，含排错） |
| `work/leica/lsposed/build.sh` | 无 Gradle 构建脚本（aapt2/javac/d8/zipalign/apksigner，可复现） |
| `work/leica/lsposed/README_BUILD.md` | 工具链与构建说明 |
| `work/leica/docs/lsposed_hook_targets.md` | Hook 目标逆向报告（两国版本、smali 证据） |
| `work/leica/lsposed/analysis/hook_targets.json` | 机器可读 hook 目标 |
| `work/leica/lsposed/analysis/evidence/` | 关键 smali 证据副本 |
| `work/leica/lsposed/hello/` | 最小 LSPosed 模块（构建管线自检用） |
