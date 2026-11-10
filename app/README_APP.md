# 徕卡一瞬 LSPosed 模块（com.nezha.leica.lsposed）用户指南

> 目标：在 **国际版 ROM** 的 Xiaomi 17 Ultra（nezha）上，让 `com.miui.mediaeditor`
> 显示并放行「徕卡一瞬 / 二次增强」云端入口，并允许注入小米账号 Cookie。
>
> ⚠️ 本项目为离线逆向研究用途，**未在真机上测试**。云端权益仍由小米服务端校验，
> 客户端模块只能打开本机门禁，**无法授予服务端权益**。风险自负。

---

## 1. 安装

1. 获取 APK：`work/leica/lsposed/dist/LeicaMoment_LSPosed_v2.3.0.apk`
   （`package=com.nezha.leica.lsposed`，versionCode 9 / versionName 2.3.0，
   minSdk 29 / targetSdk 35，v3 签名；旧版 v1.x / v2.x（≤2.2.2）已被本版取代）。
   本版为 **「无属性伪装」模式**：不修改、不伪装任何系统属性（不做属性读写，也不 hook SystemProperties）。
   v2.3.0 重点：**全屏登录页（替代不可用的内嵌小 WebView）**、**Cookie 来源模式（自动/手动/系统捕获）**、
   **系统捕获的 STS Cookie 成为自动首选**、**Cookie 体检 UA 适配（web Cookie 常见 401 的排查）**（见第 5、7 节）。
2. 在已安装 **LSPosed**（Zygisk/Riru 均可）的设备上，用文件管理器或 `adb install -r` 安装。
3. 打开 LSPosed 管理器 →「模块」→ 应能看到 **「徕卡一瞬 LSPosed」**
   （legacy Xposed 模块，本模块已带 `xposedmodule=true` / `xposedminversion=82` 元数据）。

> 如果 LSPosed 管理器列表里没有该模块：确认 APK 已安装且未卸载、LSPosed 版本支持
> legacy API，然后重启 LSPosed 管理器。

## 2. 启用模块并选择作用域

1. 在 LSPosed 管理器中启用「徕卡一瞬 LSPosed」。
2. **勾选作用域**：
   - `com.miui.mediaeditor` —— **必选**（相册编辑，门禁与 Cookie 钩子都在这里）。
   - `com.android.camera` —— 使用相机「徕卡一瞬」拍摄时**必选**（配合 Magisk CameraOnly 变体，
     见第 3 节；模块强制相机自身的两个 Java 判定方法返回 true，不涉及属性）。
   - `com.miui.gallery` —— 可选（不安装任何 hook，仅保留作用域占位、无害；
     能力判定由 mediaeditor 侧的 predicate hook 完成）。
3. 保存后建议**重启手机一次**（首次安装模块必须让 Zygote 重新加载；之后修改开关只需强制停止应用）。
4. **首次启用后请清除相册能力缓存一次**（相册是 Flutter+Rust，Rust 层会缓存“能力不可用”的旧结果）：
   设置 → 应用 → 相册 → 存储 → 「清除缓存」/「清除数据」，详见第 4 节。

## 3. 相机「徕卡一瞬」拍摄（需要 Magisk CameraOnly 变体）

本模块分成两部分，按需使用：

- **编辑器 / 云端「二次增强」**：只需本模块 + LSPosed 作用域 `com.miui.mediaeditor`（**无需 Magisk 属性模块**）。
  模块直接 hook 编辑器的 availability predicate 返回 true，不涉及任何属性伪装。
- **相机 / 「徕卡一瞬」拍摄**：需要 **Magisk「CameraOnly」变体（v1.2.0，无属性伪装）**，它只做一件事：
  只读挂载国行 MiuiCamera APK（`com.android.camera` 6.7.000280.0，Xiaomi 签名）到系统中，
  **不 resetprop、不写属性**；再配合本模块并在 LSPosed 作用域勾选 `com.android.camera`。
  相机模式/UI/资源来自国行 APK，门禁由本模块的 Java 方法 hook 打开（全部 XC_MethodReplacement → true）：
  - H1 `com.android.camera.features.mode.legendary.LegendaryEnter.support()Z` → true
    （模式 id `0x100`「徕卡一瞬」出现在模式列表/模式抽屉）；
  - H2 `Te.c.W()Z`（static）→ true（isLeicaCustomize：徕卡 UI/水印/风格入口，15+ 调用点，
    绕过其内部对 `ro.theme_customize=="LCC"` 与 `camera.debug.theme_customize.disabled` 的读取）；
  - H3 兜底：混淆设备配置类 `h5()Z` → true（H1 无法解析时的备用路径）。
  **没有 hook 任何 SystemProperties，也没有读写属性。**

**相机部分限制（重要）：**

- 厂商相机 HAL 的 legendary 能力（M9 CCD / M3 MONOPAN-50 / M10R 等 profile、
  `com.xiaomi.sessionparams.legendMode`、`com.xiaomi.legendMode.capsInfo`、
  `getLegendBokehCapsInfo` 等 session 参数）由 vendor camera pipeline 提供，Java hook **无法添加**；
  若本机 HAL 未暴露 legendary 能力，模式/UI 会出现但实际徕卡效果可能缺失或回退（例如按普通模式成像）。
- 运行时 `legend_mode` SDK / native 采集桥（`LegendaryNativeCaptureBridge` 等）在 APK dex 中仅被引用、
  未定义，可能依赖动态下载/插件/native，同样属于 Java hook 不可控范围。
- 云端「二次增强」权益属于编辑器 + 服务端，相机 hook 只负责让拍摄模式存在。
- 国际版相机 6.6 的等价 hook 未覆盖；必须使用国行 MiuiCamera APK（CameraOnly 变体）。
- 水印/EXIF 品牌字段可能仍显示国际版（相关属性消费点未 hook，且本模块不伪装属性）。

**安装步骤：**

1. 刷入 Magisk「CameraOnly」变体（v1.2.0；仅挂载国行 `MiuiCamera.apk`，不修改任何属性）。
2. 在 LSPosed 中启用本模块并勾选作用域 `com.android.camera`（编辑器用户同时勾选 `com.miui.mediaeditor`）。
3. 重启手机 / 强制停止相机进程后打开相机 → 模式栏应出现「徕卡一瞬」（模式 id `0x100`）。
4. LSPosed 日志可见 `camera hook installed: ...`。

## 4. HyperOS 4 的 Rust 组件与相册能力缓存（重要）

HyperOS 4 引入了大量 Rust 组件：

1. **系统层 Rust 组件**：`system_ext` 中有 102 个 `libhyper_os_<domain>_public/private.so`
   （account / network / app / binder / axml / …）以及 `libhyper_os_flutter.so`；
   `system_ext/framework/hyperos.rustruntime.jar` 只是一个极小（612 B）的 stub，
   唯一类是 `Lrust/core/runtime/Empty;`，真实 Rust 运行时在 native 层。
2. **相册是 Flutter + Rust**：`com.miui.gallery` 是 Dart AOT（`lib/arm64-v8a/libapp.so`）
   + Rust（`libapp_gallery.so`，约 36.5 MB，flutter_rust_bridge，crate `app_gallery`）。
   它的能力探测（`src/api/front_end/media_editor_capability.rs`）通过 MediaEditor provider
   `com.miui.mediaeditor.api` 的 `method_request_function_list` /
   `method_is_legend_available` / `method_is_xm_legend_available` 等方法查询——
   **provider 运行在 mediaeditor 进程（Java），V2 的 predicate hook 正是 hook 这条 Java 链路上的判定方法。**

**为什么这不再是问题（V2 与 v1.x 的区别）：**

- V2 **完全不涉及属性伪装**：既不 resetprop，也不 hook `SystemProperties`。因此相册
  Rust/native 组件直接读取属性（`__system_property_get`、`SystemPropertyMethodChannel` 等）
  即使绕过 Java 层也**与本模块无关**——本模块不依赖任何属性值。
- LSPosed 无法 hook Dart/Rust 代码，但本路径的判定点在 mediaeditor 的 Java 代码
  （`MediaEditorProviderForGallery` 调用的 availability predicate），V2 直接在 Java 层
  让它们返回 true。

**唯一仍需手动处理的一点——相册能力缓存：**

- 相册 Rust 层会把设备能力**缓存到 SharedPreferences**（日志：
  `capabilities SP schema mismatch … drop` / `Device capabilities cache invalidated`）；
  若旧的“不可用”结果已缓存，启用模块后入口可能仍不出现。
- **首次启用模块后清除相册能力缓存一次**：设置 → 应用 → 相册 → 存储 →
  「清除缓存」/「清除数据」（或 `adb shell pm clear com.miui.gallery`）。
  注意：清除数据会同时清掉相册的本地设置/缓存，请谨慎，之后重新打开相册。
- 相机/媒体编辑器的徕卡云端路径为 Java 实现，Cookie 注入与账号探针不受 Rust 影响。

## 5. 登录小米账号 / 获取 Cookie（v2.3.0 推荐流程）

> 关键结论：**系统账号捕获的 STS Cookie 才是云 API 接受的可靠凭据**；WebView/网页登录得到的是
> 网页 Cookie（常带 `pass_ua=web`），即使含 `serviceToken` 也可能被云 API 拒绝（常见 HTTP 401）。
> 因此 v2.3.0 把「系统捕获」做成一等来源，并支持「自动」模式（手动为空时自动使用捕获的 Cookie）。

### 5.1 推荐流程（无需手动折腾 Cookie）

1. 在**系统设置**里登录小米账号（或点模块内「登录系统小米账号」调起系统认证界面）。
2. 打开**相册 → 编辑**一次并触发云端/徕卡功能；模块会 hook App 自己取 STS Cookie 的方法，
   把 App 实际取到的 **STS Cookie 自动捕获**并保存（界面「系统账号捕获的 STS Cookie」区显示长度与键名）。
3. 回到模块：把「Cookie 来源」保持为 **自动（推荐）**，或选择 **系统捕获**；点「保存设置」。
   点「填入捕获的 Cookie」只是把捕获值填入手动输入框，便于查看/复制；一般不需要。
4. 清除相册能力缓存（设置→应用→相册→存储→清除缓存/数据），强制停止「媒体编辑器 / 相册」后实测。

「自动」模式下的注入优先级与界面一致：**手动 Cookie 优先，为空时使用系统捕获的 STS Cookie**。
所以设备已登录系统小米账号时，**什么都不填也能自动注入可靠的 STS Cookie**。

### 5.2 Cookie 来源模式

| 来源 | 行为 |
|---|---|
| **自动（推荐）** | 手动/网页 Cookie 优先；为空时自动使用系统捕获的 STS Cookie。 |
| **手动 / 网页 Cookie** | 只使用手动输入框中的 Cookie（可粘贴，或由全屏登录页返回）。 |
| **系统捕获（STS Cookie）** | 只使用 App 自己通过系统小米账号获取的 STS Cookie（最可靠，含 `hyperos_sec_common_*` / `serviceToken`）。 |

页面会显示「当前生效来源」，手动与捕获两份 Cookie 分别有组成检查（**只显示键名/长度，不显示令牌值**）。

### 5.3 全屏登录页（备用方案）

- 点「**全屏登录页（获取 Cookie）**」打开新 Activity：全屏 WebView + 顶部「返回 / 刷新 / 完成」、
  进度条、错误浮层；默认打开 `https://i.mi.com/`（未登录会自动走 passport 登录 → STS 链）。
- 顶部「完成」会把 `account.xiaomi.com` / `i.mi.com` / `gallery.i.mi.com` /
  `api.open.ai.xiaomi.com` / `xiaomi.com` 的 Cookie 合并（按名字去重）并回填到模块的手动 Cookie 框。
- `i.mi.com/sts` 回调页若报 HTTP 错误，会自动回到 `i.mi.com` 重走一次登录流程（一次性自动恢复）。
- 网页 Cookie 可能仍被云 API 拒绝（401）：此时请把来源切到「系统捕获」或使用系统账号捕获。
- 设备无系统小米账号、或想用另一个账号时，再使用此备用方案。

> **设备未登录系统小米账号时**：上传步骤会调用 AccountUtil 登录探针（CN `ga0.e.a(Context)` /
> GLOBAL `be0.e.a(Context)`），返回 null 时直接失败 `uploadFile: failed, not login`。
> 本模块已 hook 该探针：当配置了 Cookie 时，用 Cookie 中的 `userId`/`cUserId`（URL 解码后）
> 作为账号标识返回，找不到则回退字符串 `demo`，使上传流程可以继续。

## 6. 开关说明

| 开关 | 默认 | 作用 |
|---|---|---|
| 启用门禁绕过 | ✅ 开 | **直接 hook 判定方法**（不修改、不伪装任何系统属性）：mediaeditor 进程内让 CN `rt.a.g()` / `ah0.b.g()`、GLOBAL `mx.a.g()` / `r21.e.b()` 返回 true；相机进程内让 `LegendaryEnter.support()`、`Te.c.W()`（及兜底 `h5()`）返回 true。未 hook `bx.t.a()` / `ft.s.a()`（xm/传奇变体，保持原行为）。 |
| 启用 Cookie 注入 | ✅ 开 | hook 混淆类 `go.e.b()`（CN 2.10.43）/ `bs.e.b()`（GLOBAL 2.10.40），用上方配置的 Cookie 替换云端请求的 STS Cookie；Cookie 为空时保持原样。同时把该 Cookie **写入 App 进程内的静态缓存字段**（`go.e.b` / `bs.e.b`），否则只有第一个 HTTP 请求会被替换、后续请求会走缓存字段继续发系统 Cookie。 |
| （自动）登录探针 | 随 Cookie 注入 | hook AccountUtil `ga0.e.a(Context)`（CN）/ `be0.e.a(Context)`（GLOBAL）：本机没有系统小米账号时返回由 Cookie 中 `userId`/`cUserId` 推导的账号标识（无则 `demo`），避免上传步骤 `uploadFile: failed, not login`；已有系统账号时保持原值。 |
| 强制 CTA 已同意 | ❌ 关 | 仅预留：分析版本中未找到干净的 CTA 门禁方法，开启后只记录日志，仍需在 App 内手动同意 CTA。有隐私风险，默认关闭。 |

**修改 Cookie 或任意开关后，必须强制停止 `com.miui.mediaeditor`（建议连同
`com.miui.gallery` 一起）再重新打开相册/编辑：**
系统设置 → 应用 →「媒体编辑器」/「相册」→ 强制停止。
媒体编辑器的网络拦截器会把 Cookie 缓存在**进程内静态字段**中，不强制停止进程它会继续
使用旧 Cookie/旧开关状态（模块配置本身约 8 秒刷新，但进程缓存的 Cookie 无法原地失效）。
一般无需重启手机；**首次安装模块后建议重启一次手机**。

## 7. 全屏登录 / Cookie 来源 / 体检 / 界面说明（v2.3.0 更新）

模块首页为纯代码绘制的卡片式界面（深色/浅色自适应，无 XML 布局），分区：

- **① 状态总览**：门禁解锁 Hook 是否启用、Cookie 是否已配置、是否含 serviceToken、上次体检结论。
  顶部状态胶囊实时显示「门禁 / Cookie / 体检」状态。
- **② 账号与 Cookie**：
  - 「**全屏登录页（获取 Cookie）**」→ 打开独立的全屏 WebView 登录页（顶部 返回/刷新/完成、
    进度条、错误提示、`i.mi.com/sts` 失败自动恢复），完成后 Cookie 自动回填到手动输入框；
  - 「登录系统小米账号」→ 调起系统小米账号认证界面（与 App 自身登录流程相同），登录后到相册编辑
    触发一次云端请求即可让模块自动捕获 STS Cookie；
  - 「**Cookie 来源**」单选：自动（推荐）/ 手动 / 系统捕获；下方「当前生效来源」实时显示；
  - 手动 Cookie 输入框（等宽字体、可长按选择）；「填入捕获的 Cookie」「复制 Cookie」「保存设置」「清空」；
  - **Cookie 组成检查**：手动与系统捕获**分别**以彩色标签显示 `passToken` / `serviceToken` /
    `cUserId` / `userId` / `deviceId` / `passInfo` / `uLocale` / `pass_ua` 等键是否存在
    （**只显示键名与长度，不显示令牌值**）；缺少 `serviceToken` 时显示醒目警告。
- **③ Cookie 体检（只读探测）**：点「检测 Cookie 有效性」后，后台线程对以下 **GET 只读端点**
  发起请求（8 秒超时、不跟随重定向，以便识别登录跳转）：
  1. `https://i.mi.com/status/lite/setting?type=1`（强鉴权信号：未登录/无效 Cookie 返回 HTTP 401 + `{"R":401,...serviceLogin...}`）
  2. `https://i.mi.com/status/lite/setting?type=2`（同上，交叉验证）

  判定规则（v2.2.2 修正）：
  - 绿色「未被要求重新登录（Cookie 可能有效）」：HTTP 2xx 且响应无登录/401 标识、无参数错误；
  - 黄色「端点参数不足（无法据此判定）」：HTTP 2xx 但响应是内部 API 的缺参错误（如 `code:10008/10016`）；
  - 黄色「网络不可达/DNS/超时（不判定 Cookie）」：网络异常**不再被当作 Cookie 无效**；
  - 红色「服务端要求重新登录」：HTTP 401/403 或 2xx 响应含 `"R":401`/`serviceLogin`；
  - 红色「重定向到登录页」：3xx 且 Location 指向 login/passport。
  聚合结论按红/绿/黄组合给出；**只有红色才算"可能无效"**，网络/参数问题不代表 Cookie 无效。
  体检只验证 Cookie 是否被接受，云端权益（点数/设备白名单/硬件认证 token）仍由小米服务端校验；
  探测端点可能随服务端调整。

  **v2.3.0 UA 适配：** 手动/网页来源（或 Cookie 含 `pass_ua=web`）会先用 **Chrome-mobile UA** 探测
  （passport/web token 通常绑定浏览器 UA）；若首次返回 HTTP 401，会自动用 **MiuiGallery UA**
  对同一端点重试一次，并报告最佳结果（结论中标注使用的 UA）。若手动/网页 Cookie 体检 401 而系统
  捕获的 Cookie 存在，页面会明确提示切换到「系统捕获」或「自动」来源。
- **④ 功能开关**：门禁解锁（直接方法 Hook，不修改任何系统属性）、Cookie 注入；
  CTA 开关为不可用状态（本模块有意不强制 CTA）。
- **⑤ 使用说明与限制**：可折叠，内容与第 2~9 节一致。

**v2.2.2 修复说明（Cookie 体检误报）：** 旧版体检使用 `gallery.i.mi.com`（DNS 不解析）与
`/gallery/public/resource/info`（无 Cookie 也返回 200 + 缺参错误，无鉴权信号），并把网络异常/参数错误
当作"认证失败"，导致明明已拿到含 `serviceToken` 的 Cookie 却给出"可能无效"的误导结论。v2.2.2 改用
`/status/lite/setting?type=1|2`（未登录时明确的 HTTP 401 + serviceLogin 响应），并区分
绿/黄/红三级；网络与参数问题只算"无法判定"，不再误判 Cookie 无效。

**v2.2.1 修复说明（网页打不开问题）：** 旧版本（v2.2.0 及以前）的「打开登录页」使用手工拼接的
passport URL，其中 `callback=...i.mi.com/sts?sign=x...` 的 `sign=x` 只是从 ROM 字符串复制的占位符。
passport 登录成功后会带这个**无效 sign** 跳回 `i.mi.com/sts`，服务端返回 HTTP 错误页，WebView 显示
`net::ERR_HTTP_RESPONSE_CODE_FAILURE`。v2.2.1 起改为**直接打开 `https://i.mi.com/`**，由 i.mi.com 自己
发起带有效 sign 的登录/STS 跳转链；同时新增 WebView 的 HTTP 错误/加载失败提示与一次性自动恢复
（在 `i.mi.com/sts` 回调报错时自动回到 i.mi.com 重走登录流程）。若仍失败，请改用「系统账号捕获」
或手动粘贴 Cookie。

**v2.3.0 变更说明：** ① 删除原来的内嵌小 WebView（手机屏幕上基本不可用），改为独立全屏
`LoginActivity`；② 新增 Cookie 来源模式（自动/手动/系统捕获），系统捕获的 STS Cookie 成为自动首选，
并成为 Hook 注入的同优先级来源；③ 体检增加 UA 适配与 401 时切换来源的明确提示；
④ 推荐流程改为「系统账号 → 相册编辑触发自动捕获 → 自动来源 → 保存」，网页登录仅作备用。

**安全说明（重要）：**

- Cookie 属于用户自己的账号凭据，仅保存在本机模块私有目录；模块**不会上传**，也**不会把
  passToken / serviceToken 等令牌值写入日志或体检结果**（代码中只记录端点、HTTP 码、键名/长度）。
- Cookie 体检是 **GET 只读探测**（无 POST，不消耗云端配额），结果仅供参考。
- 复制到剪贴板后请注意其它应用可能读取剪贴板；测试/排障完成后建议及时清理。

## 8. 验证是否生效

1. 强制停止后打开「相册 → 编辑」，观察「徕卡一瞬 / 二次增强」入口是否出现
   （国际版原本应隐藏）。
2. 生效时 LSPosed 日志（管理器 → 日志）可见前缀 `LeicaMomentLSP:` 的记录，例如：
   - `predicate hook installed: rt.a.g()Z -> true`（CN；另有 `ah0.b.g()`）/ `mx.a.g()Z`、`r21.e.b()Z`（GLOBAL）
   - `gallery: no hooks in no-props mode (Flutter/Rust; capability probe runs in mediaeditor)`（相册进程）
   - `camera hook installed: com.android.camera.features.mode.legendary.LegendaryEnter.support()Z -> true`（相机进程）
   - `camera hook installed: Te.c.W()Z -> true` / `camera hook installed: <混淆类>.h5()Z -> true (fallback)`
   - `cookie hook installed: go.e.b()Ljava/lang/String;`（CN）或 `bs.e.b()...`（GLOBAL）
   - `cookie cache field updated: go.e.b -> user cookie (persists for subsequent requests)`
   - `injecting user cookie (N chars) via ...`
   - `account probe hook installed: ga0.e.a(android.content.Context)`（CN）或 `be0.e.a(...)`（GLOBAL）
   - `account probe hook: returned derived account id (...)`（无系统账号 + 已配置 Cookie 时）
   - `captured system-account cookie (N chars) reported to module app, ok=true`
3. 触发一次云端任务：若服务端返回 401/任务失败/排队失败，见下面限制。

## 9. 已知限制（重要）

- **云端权益在服务端校验**：点数/配额/设备白名单（`leica_support_devices`、
  `lcc_support_devices`、`mist_support_devices`）来自小米云端 CloudConfig，
  ROM 内不存在；客户端只能打开入口，**不能**授予服务端权益。预期可能出现
  「入口出现但任务失败/排队失败」。
- **硬件认证 token**：云链路还要求 `/auth/v1/hardwareAuthToken`（依赖系统小米账号
  与硬件指纹）；本模块不伪造该 token。
- **CTA 隐私协议（有意不强制）**：CTA 是 `LegendaryActivity` 正常弹出的用户同意窗口；
  「二次增强」入口是否显示只取决于上述判定方法（predicate），与 CTA 状态无关，因此**不需要**
  绕过 CTA。本模块 **不会** hook `BaseGalleryPreferences$a.a()` 等 CTA 门禁（那是隐私违规）；
  「强制 CTA」开关默认关闭且只记录日志，请用户在 App 内正常同意。
- **未登录设备的账号探针**：本模块用 Cookie 中的 `userId`/`cUserId` 伪造 AccountUtil 返回值
  以通过上传登录检查；这是客户端绕过，真实身份仍由服务端校验 Cookie，服务端可能拒绝
  无 CN 权益/无硬件认证的账号。
- **Cookie 来源差异（v2.3.0 重点）**：WebView/网页登录 Cookie ≠ App 原生 STS Cookie，网页 Cookie
  即使含 `serviceToken` 也可能被云 API 拒绝（常见 HTTP 401，尤其绑定 `pass_ua=web`/浏览器 UA 时）。
  请优先使用「系统账号捕获」，或把 Cookie 来源设为「自动 / 系统捕获」。
- **进程内 Cookie 缓存**：App 的网络拦截器把 Cookie 缓存在静态字段里，模块已同步覆盖该字段；
  但更换 Cookie/关闭注入后，**必须强制停止 mediaeditor 进程**才会完全生效（见第 6 节）。
- **相机部分限制**：见第 3 节 —— 厂商相机 HAL legendary 能力与运行时 `legend_mode` SDK 无法用 Java
  hook 添加；若 HAL 不支持，模式/UI 可能出现但实际徕卡处理会缺失/回退；必须使用国行 MiuiCamera
  APK（Magisk CameraOnly 变体）；云端权益仍由服务端校验。
- **Dart/Rust 无法 hook，但不影响本路径**：HyperOS 4 的 Rust 组件（相册
  `libapp_gallery.so`、102 个 `libhyper_os_*.so`）无法被 LSPosed hook；V2 不依赖任何
  属性伪装，判定点位于 mediaeditor 的 Java predicate，故本路径不受影响。
  相册 Rust 的能力缓存仍可能需手动清除一次（见第 4 节）。
- **未真机测试**：本项目无真机/无设备连接，所有 hook 均为静态分析结论 + try/catch 兜底；
  不同 `com.miui.mediaeditor` 版本类名可能变化，模块会记录
  `cookie target not present ...` 并跳过，不会崩溃目标 App。
- **仅作用于 mediaeditor 进程**：predicate hook 与 Cookie hook 只在该进程内生效，
  不修改系统全局状态；相机端徕卡采集界面不属本模块范围。
- 本模块不收集、不上传任何数据；Cookie 只保存在本机模块私有目录
  （ContentProvider 仅对 `com.miui.mediaeditor` / `com.miui.gallery` / 自身开放，按 uid+包名校验）。

## 10. 排错

| 现象 | 处理 |
|---|---|
| LSPosed 模块列表看不到 | 确认 APK 已安装；重启 LSPosed 管理器；确认 LSPosed 支持 legacy API。 |
| 入口仍不显示 | 确认作用域勾选 `com.miui.mediaeditor`（建议同时勾选 `com.miui.gallery`）；强制停止相册+媒体编辑器；**清除相册能力缓存**（设置→应用→相册→清除数据/缓存，或 `adb shell pm clear com.miui.gallery`）；看 LSPosed 日志是否有 `predicate hook installed: ...`；必要时重启手机。 |
| 体检显示网页 Cookie 被拒绝（HTTP 401） | 网页/passport Cookie 可能不被云 API 接受。把「Cookie 来源」切换为「系统捕获」或「自动」；或登录系统小米账号后到相册编辑一次让模块自动捕获 STS Cookie。 |
| 日志有 hook，但云端任务失败 | 多为服务端权益/设备白名单/CTA/硬件 token 问题，客户端无法解决；可先确认系统已登录小米账号并尝试系统账号捕获的 Cookie。 |
| 相机模式栏没有「徕卡一瞬」 | 确认已刷 Magisk CameraOnly 变体（CN MiuiCamera 6.7.000280.0）；LSPosed 作用域勾选 `com.android.camera`；强制停止相机；日志查 `camera hook installed:`；若 HAL 无 legendary 能力则仅 UI 出现、实际效果可能仍缺失（见第 3 节）。 |
| 模块 App 打不开/白屏 | 确认系统 Android System WebView 已启用；不登录网页也可直接粘贴 Cookie。 |
| 全屏登录页打不开/白屏 | 确认系统 Android System WebView 已启用；也可改用「登录系统小米账号」捕获或手动粘贴 Cookie。 |
