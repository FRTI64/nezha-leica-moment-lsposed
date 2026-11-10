# 徕卡一瞬 LSPosed 模块（com.nezha.leica.lsposed）用户指南

> 目标：在 **国际版 ROM** 的 Xiaomi 17 Ultra（nezha）上，让 `com.miui.mediaeditor`
> 显示并放行「徕卡一瞬 / 二次增强」云端入口，并允许注入小米账号 Cookie。
>
> ⚠️ 本项目为离线逆向研究用途，**未在真机上测试**。云端权益仍由小米服务端校验，
> 客户端模块只能打开本机门禁，**无法授予服务端权益**。风险自负。

---

## 1. 安装

1. 获取 APK：`work/leica/lsposed/dist/LeicaMoment_LSPosed_v1.2.0.apk`
   （`package=com.nezha.leica.lsposed`，versionCode 3 / versionName 1.2.0，
   minSdk 29 / targetSdk 35，v3 签名；旧版 `v1.0.0` / `v1.1.0` 已被本版取代）。
2. 在已安装 **LSPosed**（Zygisk/Riru 均可）的设备上，用文件管理器或 `adb install -r` 安装。
3. 打开 LSPosed 管理器 →「模块」→ 应能看到 **「徕卡一瞬 LSPosed」**
   （legacy Xposed 模块，本模块已带 `xposedmodule=true` / `xposedminversion=82` 元数据）。

> 如果 LSPosed 管理器列表里没有该模块：确认 APK 已安装且未卸载、LSPosed 版本支持
> legacy API，然后重启 LSPosed 管理器。

## 2. 启用模块并选择作用域

1. 在 LSPosed 管理器中启用「徕卡一瞬 LSPosed」。
2. **勾选作用域**：
   - `com.miui.mediaeditor` —— **必选**（相册编辑，门禁与 Cookie 钩子都在这里）。
   - `com.miui.gallery` —— 可选（v1.2.0 起为相册进程安装 SystemProperties 伪装 hook，
     作为其 Flutter+Rust 能力探测在 Java/JNI 侧的“安全网”；Cookie/账号 hook 只在 mediaeditor 安装）。
3. 保存后建议**重启手机一次**（首次安装模块必须让 Zygote 重新加载；之后修改开关只需强制停止应用）。
4. **首次启用后请清除相册能力缓存一次**（相册是 Flutter+Rust，Rust 层会缓存“能力不可用”的旧结果）：
   设置 → 应用 → 相册 → 存储 → 「清除缓存」/「清除数据」，详见第 3 节。

## 3. HyperOS 4 的 Rust 组件与相册能力缓存（v1.2.0 新增，重要）

HyperOS 4 引入了大量 Rust 组件，本模块因此有两处需要特别注意：

1. **系统层 Rust 组件**：`system_ext` 中有 102 个 `libhyper_os_<domain>_public/private.so`
   （account / network / app / binder / axml / …）以及 `libhyper_os_flutter.so`；
   `system_ext/framework/hyperos.rustruntime.jar` 只是一个极小（612 B）的 stub，
   唯一类是 `Lrust/core/runtime/Empty;`，真实 Rust 运行时在 native 层。
2. **相册是 Flutter + Rust**：`com.miui.gallery` 是 Dart AOT（`lib/arm64-v8a/libapp.so`）
   + Rust（`libapp_gallery.so`，约 36.5 MB，flutter_rust_bridge，crate `app_gallery`）。
   它的能力探测（`src/api/front_end/media_editor_capability.rs`）通过 MediaEditor provider
   `com.miui.mediaeditor.api` 的 `method_request_function_list` /
   `method_is_legend_available` / `method_is_xm_legend_available` 等方法查询——
   provider 运行在 mediaeditor 进程（Java），本模块的 props hook 对它有效。
   但相册 Rust 层会把设备能力**缓存到 SharedPreferences**（日志：
   `capabilities SP schema mismatch … drop` / `Device capabilities cache invalidated`），
   若旧的“不可用”结果已缓存，**启用模块后入口可能仍不出现**。

**对策：**

- **首次启用模块后清除相册能力缓存一次**：设置 → 应用 → 相册 → 存储 →
  「清除缓存」/「清除数据」（或 `adb shell pm clear com.miui.gallery`）。
  注意：清除数据会同时清掉相册的本地设置/缓存，请谨慎，之后重新打开相册。
- 相册 Rust 自身也会通过 Rust/JNI/MethodChannel 属性桥
  （`SystemPropertyMethodChannel`，channel `com.android.os.system.properties.method.channel`）
  读取 `ro.miui.build.region`、`ro.product.mod_device`、`IS_INTERNATIONAL_BUILD` 等。
  v1.2.0 已为相册进程额外安装 SystemProperties 伪装 hook（作用于其 Java/JNI 侧，
  LSPosed 日志可见 `gallery scope: SystemProperties spoof hooks installed (Rust bridge safety net)`）；
  **但 LSPosed 无法 hook Dart/Rust/native 代码**，如果 Rust 直接调用
  `__system_property_get`，Java hook 覆盖不到。
- **需要彻底覆盖 Rust/native 属性读取时，请配合 Magisk props 层**
  （本项目同系列 props-only Magisk 模块，`REGION_SPOOF=1`）：`resetprop` 修改的是真实
  系统属性区，Rust/native 组件也能看到；Xposed 模块只保证 Java 侧看到伪装值。
- 相机/媒体编辑器的徕卡云端路径已确认为 Java 实现，Cookie 注入与账号探针不受 Rust 影响。

## 4. 登录小米账号 / 获取 Cookie

模块 App 首页（打开「徕卡一瞬 LSPosed」图标）提供三种 Cookie 来源：

1. **WebView 登录抓取（首选，简单）**
   - 在首页网页中登录小米账号；
   - 点「读取登录Cookie」→ 会把 `account.xiaomi.com` / `i.mi.com` /
     `api.open.ai.xiaomi.com` / `xiaomi.com` 的 Cookie 合并（按名字去重）成
     `name=value; name=value` 形式的 Cookie 头；
   - 点「保存设置」。
2. **手动粘贴**
   - 从其它已登录设备/抓包结果复制 Cookie 头，粘贴到输入框 →「保存设置」。
3. **系统账号捕获（最可靠）**
   - 先在系统设置中登录小米账号；
   - 打开相册 → 编辑 → 触发一次云端/徕卡功能，模块会 hook App 自己取 STS Cookie 的
     方法 `b()`，把 App 实际取到的 Cookie **自动上报**并保存在本模块（第③节显示）；
   - 回到模块 App 点「把捕获的 Cookie 填入上方输入框」→「保存设置」即可复用它。

4. **调起系统小米账号登录（不需要 WebView 登录）**
   - 模块首页第③节点「登录系统小米账号」→ 调起系统小米账号认证界面
     （`AccountManager.addAccount("com.xiaomi", "passportapi", …)`，与 App 自身登录流程相同）；
   - 登录成功后打开相册 → 编辑一次并触发云端/徕卡功能，App 会自行获取 STS Cookie，
     模块自动捕获（第③节显示）；
   - 回到模块点「把捕获的 Cookie 填入上方输入框」→「保存设置」。

> 说明：WebView 登录得到的 Cookie 可能与云 API 期望的 STS Cookie 不完全一致；
> 第③节「系统账号捕获的 Cookie」来自 App 原生 AccountManager 链路，通常更可靠。
> 若云端返回 401/任务失败，可「清空」手动 Cookie，让模块回退使用 App 原生 Cookie。
>
> **设备未登录系统小米账号时**：上传步骤会调用 AccountUtil 登录探针（CN `ga0.e.a(Context)` /
> GLOBAL `be0.e.a(Context)`），返回 null 时直接失败 `uploadFile: failed, not login`。
> 本模块已 hook 该探针：当配置了 Cookie 时，用 Cookie 中的 `userId`/`cUserId`（URL 解码后）
> 作为账号标识返回，找不到则回退字符串 `demo`，使上传流程可以继续。

## 5. 开关说明

| 开关 | 默认 | 作用 |
|---|---|---|
| 启用门禁绕过 | ✅ 开 | 在 mediaeditor 进程内 hook `android.os.SystemProperties.get/getBoolean/getInt` 并伪装：`ro.miui.build.region=cn`、`ro.theme_customize=LCC`、`ro.theme_leica=true`、`ro.boot.product.theme_customize=P1_Leica`、`ro.special_edition=true`。使 `method_is_legend_available` 判定为真。**不修改系统属性文件。** |
| 启用 Cookie 注入 | ✅ 开 | hook 混淆类 `go.e.b()`（CN 2.10.43）/ `bs.e.b()`（GLOBAL 2.10.40），用上方配置的 Cookie 替换云端请求的 STS Cookie；Cookie 为空时保持原样。同时把该 Cookie **写入 App 进程内的静态缓存字段**（`go.e.b` / `bs.e.b`），否则只有第一个 HTTP 请求会被替换、后续请求会走缓存字段继续发系统 Cookie。 |
| （自动）登录探针 | 随 Cookie 注入 | hook AccountUtil `ga0.e.a(Context)`（CN）/ `be0.e.a(Context)`（GLOBAL）：本机没有系统小米账号时返回由 Cookie 中 `userId`/`cUserId` 推导的账号标识（无则 `demo`），避免上传步骤 `uploadFile: failed, not login`；已有系统账号时保持原值。 |
| 强制 CTA 已同意 | ❌ 关 | 仅预留：分析版本中未找到干净的 CTA 门禁方法，开启后只记录日志，仍需在 App 内手动同意 CTA。有隐私风险，默认关闭。 |

**修改 Cookie 或任意开关后，必须强制停止 `com.miui.mediaeditor`（建议连同
`com.miui.gallery` 一起）再重新打开相册/编辑：**
系统设置 → 应用 →「媒体编辑器」/「相册」→ 强制停止。
媒体编辑器的网络拦截器会把 Cookie 缓存在**进程内静态字段**中，不强制停止进程它会继续
使用旧 Cookie/旧开关状态（模块配置本身约 8 秒刷新，但进程缓存的 Cookie 无法原地失效）。
一般无需重启手机；**首次安装模块后建议重启一次手机**。

## 6. 验证是否生效

1. 强制停止后打开「相册 → 编辑」，观察「徕卡一瞬 / 二次增强」入口是否出现
   （国际版原本应隐藏）。
2. 生效时 LSPosed 日志（管理器 → 日志）可见前缀 `LeicaMomentLSP:` 的记录，例如：
   - `hooked SystemProperties.get(String)`
   - `gallery scope: SystemProperties spoof hooks installed (Rust bridge safety net)`（相册进程）
   - `cookie hook installed: go.e.b()Ljava/lang/String;`（CN）或 `bs.e.b()...`（GLOBAL）
   - `cookie cache field updated: go.e.b -> user cookie (persists for subsequent requests)`
   - `injecting user cookie (N chars) via ...`
   - `account probe hook installed: ga0.e.a(android.content.Context)`（CN）或 `be0.e.a(...)`（GLOBAL）
   - `account probe hook: returned derived account id (...)`（无系统账号 + 已配置 Cookie 时）
   - `captured system-account cookie (N chars) reported to module app, ok=true`
3. 触发一次云端任务：若服务端返回 401/任务失败/排队失败，见下面限制。

## 7. 已知限制（重要）

- **云端权益在服务端校验**：点数/配额/设备白名单（`leica_support_devices`、
  `lcc_support_devices`、`mist_support_devices`）来自小米云端 CloudConfig，
  ROM 内不存在；客户端只能打开入口，**不能**授予服务端权益。预期可能出现
  「入口出现但任务失败/排队失败」。
- **硬件认证 token**：云链路还要求 `/auth/v1/hardwareAuthToken`（依赖系统小米账号
  与硬件指纹）；本模块不伪造该 token。
- **CTA 隐私协议（有意不强制）**：CTA 是 `LegendaryActivity` 正常弹出的用户同意窗口；
  「二次增强」入口是否显示只取决于系统属性伪装（props），与 CTA 状态无关，因此**不需要**
  绕过 CTA。本模块 **不会** hook `BaseGalleryPreferences$a.a()` 等 CTA 门禁（那是隐私违规）；
  「强制 CTA」开关默认关闭且只记录日志，请用户在 App 内正常同意。
- **未登录设备的账号探针**：本模块用 Cookie 中的 `userId`/`cUserId` 伪造 AccountUtil 返回值
  以通过上传登录检查；这是客户端绕过，真实身份仍由服务端校验 Cookie，服务端可能拒绝
  无 CN 权益/无硬件认证的账号。
- **Cookie 来源差异**：WebView 登录 Cookie ≠ 原生 STS Cookie；优先用「系统账号捕获」。
- **进程内 Cookie 缓存**：App 的网络拦截器把 Cookie 缓存在静态字段里，模块已同步覆盖该字段；
  但更换 Cookie/关闭注入后，**必须强制停止 mediaeditor 进程**才会完全生效（见第 5 节）。
- **Rust/native 覆盖有限**：HyperOS 4 的 Rust 组件（含相册 `libapp_gallery.so`、102 个
  `libhyper_os_*.so`）无法被 LSPosed hook；本模块对相册只做 Java/JNI 侧的 SystemProperties
  伪装，Rust 直接读 native 属性时无效。彻底覆盖请配合 Magisk props/resetprop 模块；
  相册 Rust 的能力缓存需手动清除（见第 3 节）。
- **未真机测试**：本项目无真机/无设备连接，所有 hook 均为静态分析结论 + try/catch 兜底；
  不同 `com.miui.mediaeditor` 版本类名可能变化，模块会记录
  `cookie target not present ...` 并跳过，不会崩溃目标 App。
- **仅作用于本进程**：SystemProperties 伪装只影响 mediaeditor 进程内的读取，
  不改系统全局属性；相机端徕卡采集界面不属本模块范围。
- 本模块不收集、不上传任何数据；Cookie 只保存在本机模块私有目录
  （ContentProvider 仅对 `com.miui.mediaeditor` / `com.miui.gallery` / 自身开放，按 uid+包名校验）。

## 8. 排错

| 现象 | 处理 |
|---|---|
| LSPosed 模块列表看不到 | 确认 APK 已安装；重启 LSPosed 管理器；确认 LSPosed 支持 legacy API。 |
| 入口仍不显示 | 确认作用域勾选 `com.miui.mediaeditor`（建议同时勾选 `com.miui.gallery`）；强制停止相册+媒体编辑器；**清除相册能力缓存**（设置→应用→相册→清除数据/缓存，或 `adb shell pm clear com.miui.gallery`）；看 LSPosed 日志是否有 `hooked SystemProperties` / `gallery scope: SystemProperties spoof hooks installed`；必要时重启手机；彻底覆盖 Rust 属性读取请配合 Magisk props 模块（见第 3 节）。 |
| 日志有 hook，但云端任务失败 | 多为服务端权益/设备白名单/CTA/硬件 token 问题，客户端无法解决；可先确认系统已登录小米账号并尝试系统账号捕获的 Cookie。 |
| 模块 App 打不开/白屏 | 确认系统 Android System WebView 已启用；不登录网页也可直接粘贴 Cookie。 |
