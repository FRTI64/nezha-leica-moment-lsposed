# 徕卡一瞬 LSPosed 模块（com.nezha.leica.lsposed）用户指南

> 目标：在 **国际版 ROM** 的 Xiaomi 17 Ultra（nezha）上，让 `com.miui.mediaeditor`
> 显示并放行「徕卡一瞬 / 二次增强」云端入口，并允许注入小米账号 Cookie。
>
> ⚠️ 本项目为离线逆向研究用途，**未在真机上测试**。云端权益仍由小米服务端校验，
> 客户端模块只能打开本机门禁，**无法授予服务端权益**。风险自负。

---

## 1. 安装

1. 获取 APK：`work/leica/lsposed/dist/LeicaMoment_LSPosed_v2.5.1.apk`
   （`package=com.nezha.leica.lsposed`，versionCode 15 / versionName 2.5.1，
   minSdk 29 / targetSdk 35，v3 签名；旧版 v1.x / v2.x（≤2.5.0）已被本版取代）。
   本版为 **「无属性伪装」模式**：不修改、不伪装任何系统属性（不做属性读写，也不 hook SystemProperties）。
   v2.4.0 重点：**彻底移除系统账号 Cookie 路径**（不读取/不捕获/不保存系统账号 STS Cookie）；
   Cookie 来源仅限**用户自己**手动粘贴或本模块全屏网页登录；新增**网页会话自检**（evaluateJavascript）。
   v2.4.1 新增：**真实目标 API 只读探测**（`api.open.ai.xiaomi.com/aiPhotoCloud/api/legendV2/list`，
   POST body `{}`，不创建任务/不收费）+ AI 平台桌面 Chrome UA 重试 + **复制诊断信息** +
   Cookie 合并按域名优先级去重（i.mi.com > api.open.ai.xiaomi.com > gallery.i.mi.com > account.xiaomi.com > xiaomi.com）。
   v2.4.2 修正判定：**micloud HTTP 2xx（无登录标识）= 鉴权通过（绿色）**，业务参数不足不再降级为黄色；
   AI 云平台行改为**仅供参考**（基址由云端配置动态下发，404/405 只表示路径/基址不匹配，不参与鉴权结论）。
   v2.4.3 新增 **云端请求轨迹（最近12条）**：hook 媒体编辑器的云请求拦截器，记录 App 实际请求的
   method / host / encodedPath（**不含查询参数、签名、令牌、请求体**）+ HTTP 码 + 是否带 Cookie，
   用于在真机上确认真实 AI 云端点与返回码。
   v2.5.0 新增 **②.5 强制启动二次增强（排障）**：相册入口不显示时，可直接选择照片/使用相册最新照片，
   显式启动 `com.miui.mediaeditor/com.miui.mediaeditor.legendary.LegendaryActivity`（带
   READ Uri 授权；失败自动尝试备用组件名），或复制 adb 启动命令手动执行。
   v2.5.1 新增 **legend 版本回落注入（option B，默认开启）**：hook mediaeditor 的 LegendaryVersionResolver
   工厂 `lv.j0.a/b`，使无一瞬 maker note 的照片也能走 cleaned upload 兜底、越过「图片解析失败」到达云端请求
   （结果被替换为 V2/M3/TRUE/f 组合）。可在 ④ 功能开关 关闭 (`force_legend_fallback`)。
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

## 5. 登录小米账号 / 获取 Cookie（仅限用户 Cookie，v2.4.0）

> **重要前提：本模块不使用系统账号 Cookie。** 模块不读取、不捕获、不保存、不上报应用通过系统
> 小米账号获取的 STS Cookie。Cookie 来源**仅限用户自己**：① 手动粘贴；② 在本模块的**全屏登录页**
> 中用网页登录后导出。若没有配置用户 Cookie，Cookie 注入会保持原样（pass-through），
> 不会采集任何系统账号凭据。

### 5.1 推荐流程

1. 确保 LSPosed 已启用本模块、作用域勾选 `com.miui.mediaeditor`（相机用户另勾选 `com.android.camera`）。
2. 点「**全屏登录页（获取 Cookie）**」→ 在全屏网页中登录小米账号；如需 STS，点网页中的 i.mi.com
   完成 STS（登录/STS 完成后网页顶部会显示自检状态）。
3. 点右上角「**完成**」：网页会自动运行一次**网页会话自检**（`evaluateJavascript` fetch 到
   `i.mi.com/status/lite/setting`），并把 Cookie 自动回填到模块手动输入框。
   - 自检返回 **401**：网页会话未通过鉴权（未登录或需要 STS），请先完成登录/STS 再点完成；
   - 自检返回 **200**：网页会话有效，可以继续。
4. 回到模块点「检测 Cookie 有效性」（只读 GET 体检）。
5. 清除相册能力缓存（设置→应用→相册→存储→清除缓存/数据），强制停止「媒体编辑器 / 相册」后实测。

### 5.2 网页会话自检 vs 模块体检（为什么可能都是 401）

- **网页会话自检**：在登录 WebView 内部、用网页会话访问 `i.mi.com/status/lite/setting`。
  401 = 网页本身未登录/需要 STS；200 = 网页会话有效。
- **模块体检**：用**导出的 Cookie** 在 App 进程外访问同一个端点，带 `Referer: https://i.mi.com/`
  与 `Accept-Language: zh-CN,zh;q=0.9`，并在 `pass_ua=web` 等 web Cookie 时优先使用 Chrome-mobile UA、
  HTTP 401 时换另一种 UA 重试一次。
- 若**网页自检 200 但模块体检 401**：说明网页 Cookie 与 App 云 API 的鉴权链路不同
  （App 云 API 使用 App 原生账号/STS 链路，本模块不采用系统账号 Cookie），
  导出的网页 Cookie 可能不会被该云 API 接受；这是服务端鉴权差异，客户端无法绕过。

### 5.3 全屏登录页说明

- 全屏 WebView + 顶部「返回 / 刷新 / 自检 / 完成」、进度条、错误浮层；默认打开 `https://i.mi.com/`
  （未登录会自动走 passport 登录 → STS 链；不使用手工拼接的 sign=x URL）。
- `i.mi.com/sts` 回调页若报 HTTP 错误，会自动回到 `i.mi.com` 重走一次登录流程（一次性自动恢复）。
- 「完成」导出 Cookie 时合并 5 个域名并按**域名优先级**去重（先出现者胜）：
  `i.mi.com` > `api.open.ai.xiaomi.com` > `gallery.i.mi.com` > `account.xiaomi.com` > `xiaomi.com`。
  这样云 API 域名的同名 Cookie 优先于 account/passport 域；若发现跨域重复键，会记录键名
  （不记录值）并在组成检查中显示「来源域名 + 重复键」提示。
- 若仍需 401，请确认网页内已完成登录/STS，或手动粘贴你确认有效的用户 Cookie。

> **设备未登录系统小米账号时**：上传步骤会调用 AccountUtil 登录探针（CN `ga0.e.a(Context)` /
> GLOBAL `be0.e.a(Context)`），返回 null 时直接失败 `uploadFile: failed, not login`。
> 本模块已 hook 该探针：当配置了用户 Cookie 时，用 Cookie 中的 `userId`/`cUserId`（URL 解码后）
> 作为账号标识返回，找不到则回退字符串 `demo`，使上传流程可以继续。
> 该探针只读取**用户配置的 Cookie**，不涉及系统账号 Cookie。

## 6. 开关说明

| 开关 | 默认 | 作用 |
|---|---|---|
| 启用门禁绕过 | ✅ 开 | **直接 hook 判定方法**（不修改、不伪装任何系统属性）：mediaeditor 进程内让 CN `rt.a.g()` / `ah0.b.g()`、GLOBAL `mx.a.g()` / `r21.e.b()` 返回 true；相机进程内让 `LegendaryEnter.support()`、`Te.c.W()`（及兜底 `h5()`）返回 true。未 hook `bx.t.a()` / `ft.s.a()`（xm/传奇变体，保持原行为）。 |
| 启用 Cookie 注入 | ✅ 开 | hook 混淆类 `go.e.b()`（CN 2.10.43）/ `bs.e.b()`（GLOBAL 2.10.40），仅用**用户配置的 Cookie** 替换云端请求的 Cookie；用户 Cookie 为空时保持原样、**不采集/不替换**系统账号 Cookie。同时把该 Cookie 写入 App 进程内的静态缓存字段（`go.e.b` / `bs.e.b`），否则只有第一个请求会被替换。 |
| （自动）登录探针 | 随 Cookie 注入 | hook AccountUtil `ga0.e.a(Context)`（CN）/ `be0.e.a(Context)`（GLOBAL）：本机没有系统小米账号时返回由**用户 Cookie** 中 `userId`/`cUserId` 推导的账号标识（无则 `demo`），避免上传步骤 `uploadFile: failed, not login`；已有系统账号时保持原值。只读取用户 Cookie。 |
| 强制 CTA 已同意 | ❌ 关 | 仅预留：分析版本中未找到干净的 CTA 门禁方法，开启后只记录日志，仍需在 App 内手动同意 CTA。有隐私风险，默认关闭。 |

**修改 Cookie 或任意开关后，必须强制停止 `com.miui.mediaeditor`（建议连同
`com.miui.gallery` 一起）再重新打开相册/编辑：**
系统设置 → 应用 →「媒体编辑器」/「相册」→ 强制停止。
媒体编辑器的网络拦截器会把 Cookie 缓存在**进程内静态字段**中，不强制停止进程它会继续
使用旧 Cookie/旧开关状态（模块配置本身约 8 秒刷新，但进程缓存的 Cookie 无法原地失效）。
一般无需重启手机；**首次安装模块后建议重启一次手机**。

## 7. 界面说明：全屏登录 / 用户 Cookie / 体检（v2.4.0 更新）

模块首页为纯代码绘制的卡片式界面（深色/浅色自适应，无 XML 布局），分区：

- **① 状态总览**：顶部醒目提示「本模块不使用系统账号 Cookie；Cookie 来源仅限用户手动粘贴或本模块
  全屏网页登录」。显示门禁解锁 Hook、Cookie 是否配置、是否含 serviceToken、上次体检结论；
  顶部状态胶囊实时显示「门禁 / Cookie / 体检」。
- **② 账号与 Cookie（用户 Cookie）**：
  - 「**全屏登录页（获取 Cookie）**」→ 打开独立全屏 `LoginActivity`（顶部 返回/刷新/自检/完成、
    进度条、错误浮层、`i.mi.com/sts` 失败自动恢复）→ 完成后自动自检并回填 Cookie；
  - 用户 Cookie 输入框（等宽字体、可长按选择）；「复制 Cookie」「保存设置」「清空」「刷新状态」；
  - **Cookie 组成检查**：彩色标签显示 `passToken` / `serviceToken` / `cUserId` / `userId` /
    `deviceId` / `passInfo` / `uLocale` / `pass_ua` 等键是否存在（**只显示键名与长度，不显示令牌值**）；
    缺少 `serviceToken` 时显示醒目警告。
- **②.5 强制启动二次增强（排障）**：相册里「二次增强」入口不显示时使用。
  - 「选择照片并启动」→ 系统照片选择器（ACTION_OPEN_DOCUMENT，image/*）→ 用显式 Component 启动
    `com.miui.mediaeditor/com.miui.mediaeditor.legendary.LegendaryActivity`，携带照片 Uri 与
    `FLAG_GRANT_READ_URI_PERMISSION`（ActivityNotFound/SecurityException 时自动尝试 `.legendary.LegendaryActivity`
    等备用组件名）；
  - 「用相册最新照片启动」→ 运行时请求 `READ_MEDIA_IMAGES`（Android 13+；旧版本回退
    `READ_EXTERNAL_STORAGE`）→ MediaStore 查询最新照片（`_id`/`DISPLAY_NAME`）→ 同样方式启动；
  - 「复制 adb 启动命令」→ 复制 `am start -n <component> -d <uri> --grant-read-uri-permission` 供手动排障；
  - 状态区显示启动的组件、Uri scheme（`content://…`）与提醒：进入页面点「开始处理」后，回模块点
    「刷新云端轨迹」查看 App 实际请求的云地址与 HTTP 码。
  - **legend 版本回落（v2.5.1，默认开启）**：模块会 hook mediaeditor 的 LegendaryVersionResolver 工厂
    （`lv.j0.a` / `lv.j0.b`），把解析结果替换为 `lv.d0(V2, M3, Boolean.TRUE, legendary.e.f)`，
    使**没有一瞬 maker note 元数据**的照片也能走 cleaned upload 兜底路径，越过「图片解析失败」并尝试发出
    云端请求。风险：可能产生一次云请求/消耗云端额度，服务端可能拒绝（鉴权/权益/设备白名单）；
    可在「④ 功能开关」中关闭 `force_legend_fallback`。
  - 说明：该卡片只调用 App 自己导出的 Activity，不修改任何系统属性；照片仅临时授权给
    com.miui.mediaeditor 读取。
- **③ Cookie 体检（只读探测）**：点「检测 Cookie 有效性」后，后台线程执行
  **2 个 i.mi.com micloud GET**（`/status/lite/setting?type=1|2`）+ **1 个真实目标 API POST**
  （`https://api.open.ai.xiaomi.com/aiPhotoCloud/api/legendV2/list`，body `{}`，只读 list，
  **绝不调用 create/billing/收费接口**）。全部 8 秒超时、不跟随重定向，
  请求带 `Referer: https://i.mi.com/`、`Accept-Language: zh-CN,zh;q=0.9` 和 UA（AI 平台行会额外用桌面 Chrome UA 重试）：
  - `pass_ua=web` 等网页 Cookie 优先使用 **Chrome-mobile UA**，否则先用 MiuiGallery UA；
  - 首次 HTTP 401 时用**另一种 UA 重试同一端点一次**，报告最佳结果并在结论中标注 UA；
  - micloud 判定规则（v2.4.2）：**绿色 = HTTP 2xx 且无登录/401 标识 = 鉴权通过**（「未被要求重新登录
    （鉴权通过，HTTP 200）」）；若响应正文是业务参数不足 JSON，仅追加「业务参数不足，不影响鉴权结论」，
    仍保持绿色；黄色 = 网络问题/非登录重定向（不判定 Cookie 无效）；红色 = 401/403 或响应含
    `"R":401`/`serviceLogin` 或重定向到登录页；
  - **「参考：AI 云平台（基址由云端配置动态下发，可能 404）」行**：仅作参考，**不参与鉴权结论**。
    该基址由云端配置（`AIConfig.url_config`）动态下发，硬编码路径可能 404；因此 404/405 → 黄色
    「路径/基址不匹配（不代表 Cookie 无效）」；401/403 或 `"R":401`/`serviceLogin` → 红色（仅供参考）；
    2xx → 绿色（仅参考）；其它 → 黄色。
  - **聚合结论（只看 micloud）**：两个 micloud 端点都 2xx → 「Cookie 已被小米云鉴权接受（micloud HTTP 200）
    → 鉴权通过；请直接实测相册编辑「二次增强」，能否出图取决于服务端权益/设备白名单/硬件认证 token」；
    任一 micloud 401/403 → 红色结论；仅网络问题 → 无法判定（不代表 Cookie 无效）。结论中会附带
    「AI 云平台基址由云端配置动态下发，404 不是鉴权信号」的参考说明。若合并时存在跨域重复 Cookie 键，
    结论会提示可能与此有关。
  - 若上次由全屏登录页返回了网页会话自检结果：体检 401 且自检 200 时，结论会说明「网页 Cookie 与
    App 云 API 鉴权链路不同」；自检 401 时提示先完成网页登录/STS。
  - 「**复制诊断信息**」：只复制版本、各探测端点的 HTTP 码/UA 类型、自检状态、Cookie 键名与长度、
    来源域名与重复键信息，以及**云端请求轨迹**；**不包含任何令牌值**，便于反馈排障。
  - **云端请求轨迹（最近12条）**：在 Cookie 体检卡片内显示，点「刷新云端轨迹」从模块 Provider 读取。
    轨迹来自媒体编辑器进程内对云请求拦截器的 hook：只记录 `method host encodedPath -> HTTP code cookie=0/1`
    （路径仅匹配 aiPhotoCloud / legend / hardwareAuth / hardwareNonce / auth/v1 / billing），
    **绝不记录查询参数、签名、Cookie 值、请求头值或请求体**。使用方式：先打开相册编辑并触发一次
    「二次增强」，再刷新轨迹/复制诊断，即可在真机看到 App 实际请求的云地址与 HTTP 码。
- **④ 功能开关**：门禁解锁（直接方法 Hook，不修改任何系统属性）、Cookie 注入（仅用户 Cookie）；
  CTA 开关为不可用状态（本模块有意不强制 CTA）。
- **⑤ 使用说明与限制**：可折叠，内容与第 2~9 节一致。

**保留的既有修复：**
- v2.2.1：登录页直接打开 `https://i.mi.com/`（不再使用 sign=x 的拼接 URL），并在 `i.mi.com/sts`
  回调报错时一次性自动回到 i.mi.com 重走登录流程。
- v2.2.2：体检改用 `/status/lite/setting?type=1|2` 真实鉴权信号端点，网络/参数问题不再误判
  Cookie 无效（只有红色才算"可能无效"）。

**v2.4.0 变更说明：** ① 删除「系统账号捕获的 Cookie」卡片、「登录系统小米账号」按钮与
「填入捕获的 Cookie」；② 删除 Cookie 来源模式（自动/手动/系统捕获）与捕获回退，Cookie 来源
仅限用户；③ Hook 端不再上报/采集应用获取的系统账号 STS Cookie，无用户 Cookie 时 pass-through；
④ 新增全屏登录页「网页会话自检」（evaluateJavascript fetch + JS bridge），并在「完成」时自动执行；
⑤ 体检增加 Referer / Accept-Language / UA 适配。

**安全说明（重要）：**

- Cookie 属于用户自己的账号凭据，仅保存在本机模块私有目录；模块**不会上传**，也**不会把
  passToken / serviceToken 等令牌值写入日志或体检结果**（代码中只记录端点、HTTP 码、键名/长度）。
- 模块**不使用系统账号 Cookie**：不 hook/读取 AccountManager 的 STS Cookie 内容，也不接收应用
  回传的 Cookie。
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
   - `account probe hook: returned derived account id (...)`（无系统账号 + 已配置用户 Cookie 时）
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
- **用户 Cookie 与 App 云 API 的鉴权差异（v2.4.0 重点）**：本模块按用户要求**不使用系统账号 Cookie**，
  仅使用用户手动粘贴/网页登录导出的 Cookie。网页 Cookie 即使含 `serviceToken` 也可能被 App 云 API
  拒绝（常见 HTTP 401，尤其绑定 `pass_ua=web`/浏览器 UA 时）。若全屏登录页「网页会话自检」为 200
  但模块体检为 401，即为网页 Cookie 与 App 原生云 API 鉴权链路的差异，属服务端行为，客户端无法绕过。
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
| 「图片解析失败」（legendInfo is null） | v2.5.1 默认启用 legend 版本回落注入后，无一瞬 maker note 的照片会走 cleaned upload 兜底并尝试云端请求；若仍失败请确认 ④ 功能开关中 `force_legend_fallback` 为开启，并查看「刷新云端轨迹」中的请求与 HTTP 码。 |
| 相册没有「二次增强」入口 | 用「②.5 强制启动二次增强（排障）」：先拍一张正常亮度、未编辑的徕卡一瞬照片，再「选择照片并启动」或「用相册最新照片启动」；进入页面点「开始处理」后回模块刷新云端轨迹。 |
| 想确认 App 真实请求的云地址/HTTP 码 | 打开相册编辑触发一次「二次增强」→ 回模块 →「刷新云端轨迹」或「复制诊断信息」。轨迹只含 method/host/path/HTTP 码/是否带 Cookie，不含令牌/查询/请求体。 |
| 体检 micloud 返回 HTTP 200（绿色「鉴权通过」） | Cookie 已被小米云鉴权接受；请直接实测相册编辑「二次增强」。能否出图仍取决于服务端权益/设备白名单/硬件认证 token（客户端无法验证）。AI 云平台参考行若为 404 属正常（基址由云端动态下发），不影响鉴权结论。 |
| 体检显示网页 Cookie 被拒绝（HTTP 401） | 先看**AI 云平台行**：若 i.mi.com 401 但 AI 云平台为绿/黄 → 实际二次增强可能仍可用（以实测为准）；若 AI 云平台也 401 → 网页 Cookie 不被小米云鉴权接受（服务端行为；不使用系统账号 Cookie 时客户端无法修复）。同时看全屏登录页「自检」：401 请先完成网页登录/STS；可点「复制诊断信息」查看各端点/UA/重复键详情（不含令牌值）。 |
| 日志有 hook，但云端任务失败 | 多为服务端权益/设备白名单/CTA/硬件 token 或用户 Cookie 鉴权链路差异问题，客户端无法解决。 |
| 相机模式栏没有「徕卡一瞬」 | 确认已刷 Magisk CameraOnly 变体（CN MiuiCamera 6.7.000280.0）；LSPosed 作用域勾选 `com.android.camera`；强制停止相机；日志查 `camera hook installed:`；若 HAL 无 legendary 能力则仅 UI 出现、实际效果可能仍缺失（见第 3 节）。 |
| 模块 App 打不开/白屏 | 确认系统 Android System WebView 已启用；不登录网页也可直接粘贴 Cookie。 |
| 全屏登录页打不开/白屏 | 确认系统 Android System WebView 已启用；也可手动粘贴用户 Cookie。 |
