# LSPosed 模块 v2.4.0：**不伪装任何系统属性**的小米账号 Cookie（含体检/STS 助手）+ 徕卡一瞬/二次增强解锁

> 交付物：`out/leica_moment_port/lsposed/LeicaMoment_LSPosed_v2.1.0.apk`（源：`work/leica/lsposed/dist/LeicaMoment_LSPosed_v2.1.0.apk`；legacy Xposed 模块，LSPosed 可用）
> 版本：`versionCode 10 / versionName 2.4.0`，45,668 B，sha256 `a8526e067c2e0fe890b237eab3fab7876c9e45df53f58638fac366dc39f67593`（两次构建字节一致）
> 目标：小米 17 Ultra（`nezha` / `nezha_global`），HyperOS 4 / Android 17；`com.miui.mediaeditor`（相册-编辑）、可选 `com.android.camera`（配 Magisk CameraOnly）、`com.miui.gallery`（占位）
>
> ✅ **本版本完全不伪装系统属性**：不做 `resetprop`、不 hook `android.os.SystemProperties`、不修改任何 `ro.*` 值。改为**直接 hook App 内部的门禁判定方法**返回 `true`（只影响被 hook 的两个进程）。仍为静态分析产物，**未真机运行验证**。

> **v2.4.0 更新（按用户要求：不使用系统账号 Cookie）**：
> ① **彻底移除系统账号 Cookie 路径**：删除「系统账号捕获的 Cookie」「登录系统小米账号」「填入捕获的 Cookie」、Cookie 来源模式（auto/manual/captured）及其回退逻辑；HookEntry 不再捕获/上报 App 原生的系统账号 STS Cookie；无用户 Cookie 时原逻辑直接透传，不读取不记录；
> ② Cookie 来源仅限：**用户手动粘贴**或**本模块全屏网页登录**导出的 Cookie；
> ③ 新增**网页会话自检**（LoginActivity 内 `evaluateJavascript` 发起带凭据的 fetch 到 `i.mi.com/status/lite/setting?type=1`，经 `@JavascriptInterface` 回传 HTTP 码与脱敏摘要）——用于区分"网页未登录/STS 未完成（401）"与"网页会话有效但导出的 Cookie 被 App 云 API 拒绝"；
> ④ 体检保留 UA 自适应，并增加 `Referer: https://i.mi.com/`、`Accept-Language: zh-CN`；若自检 200 而体检 401，UI 会明确说明这是网页 Cookie 与 App 云 API 鉴权链路差异（服务端行为，客户端无法修复）。

> **v2.3.0 更新（解决 WebView 难用 + 网页 Cookie 401）**：
> ① 新增**全屏登录 Activity**（`LoginActivity`：顶栏 返回/刷新/完成、进度条、错误提示、返回键处理、`i.mi.com/sts` 一次性自动恢复），替代原先内嵌的小 WebView；
> ② 新增 **Cookie 来源模式**：`auto`（默认，手动→系统捕获）/`manual`/`captured`；**Hook 注入按同一优先级**——系统账号已登录的设备可**零操作自动注入 App 原生 STS Cookie**（`hyperos_sec_common_*`/`serviceToken` 等）；
> ③ 体检对 Web 来源 Cookie 做 **UA 自适应**（`pass_ua=web` 先 Web UA，401 后重试 MiuiGallery UA），并在"网页 Cookie 被 401 而系统捕获 Cookie 存在"时明确提示切换来源；
> ④ 推荐流程改为：系统设置登录小米账号 → 打开相册-编辑一次（自动捕获 STS Cookie）→ 来源选「自动/系统捕获」→ 保存 → 清相册缓存 → 强制停止 → 实测；全屏 WebView 仅作备用。

> **v2.2.2 修复（Cookie 体检误报）**：旧版体检把 `gallery.i.mi.com`（DNS 不解析）与
> `/gallery/public/resource/info`（无 Cookie 也返回 200 + 缺参错误，无鉴权信号）当作判定依据，且把网络异常/参数错误
> 当作"认证失败"，导致已含 `serviceToken` 的有效 Cookie 被判为"可能无效"。v2.2.2 改用真实鉴权信号端点
> `https://i.mi.com/status/lite/setting?type=1|2`（未登录/无效 Cookie 明确返回 HTTP 401 + `{"R":401,...serviceLogin...}`），
> 并改为三级判定：绿色=未被要求重新登录；黄色=网络/端点参数问题（**不判定 Cookie**）；红色=被拒绝/重定向登录。
>
> **v2.2.1 修复（网页打不开 / `net::ERR_HTTP_RESPONSE_CODE_FAILURE`）**：旧版「打开登录页」使用手工拼接的
> passport URL，其中 `callback=...i.mi.com/sts?sign=x...` 的 `sign=x` 只是从 ROM 复制过来的占位符；passport 登录
> 后会带无效 sign 跳回 `i.mi.com/sts`，服务端返回 HTTP 错误页。v2.2.1 改为**直接打开 `https://i.mi.com/`**
> （由 i.mi.com 自己发起带有效 sign 的登录/STS 跳转链），并新增 WebView `onReceivedHttpError`/`onReceivedError`
> 友好提示 + `i.mi.com/sts` 回调报错时的一次性自动恢复（自动回到 i.mi.com 重走登录）。

---

## 1. 原理：hook 判定方法，而不是伪装属性

| 目标 | 原判定（读属性） | 本模块做法 |
|---|---|---|
| 相册编辑「二次增强」入口 | `MediaEditorProviderForGallery` 计算 `method_is_legend_available = mx.a.g() && r21.e.b()`（其中 `mx.a.g()` 自检 `Build.DEVICE=="nezha" && ro.theme_customize=="LCC"`，`r21.e.b()` 自检 `ro.miui.build.region=="cn"`） | 直接 hook 这两个布尔方法 → 返回 `true`（国行 2.10.43 对应 `rt.a.g()` / `ah0.b.g()`） |
| 相机「徕卡一瞬」模式/徕卡 UI | `LegendaryEnter.support()` 与 `Te.c.W()`（`W()` 内部读 `Va/b` 缓存的 `ro.theme_customize=="LCC"` / `camera.debug.theme_customize.disabled`） | 直接 hook `LegendaryEnter.support()Z` → true、`Te.c.W()Z`(static) → true |

因此：**系统属性保持原样**，其它应用/系统服务完全不受影响（不存在 region 伪装的 OTA/区域服务副作用，也不影响 Play Integrity 所依赖的 `ro.*` 值）。

剩余流程（Cookie、登录、服务端）不涉及属性，保持不变。

---

## 2. 功能与 Hook 清单（v2.1.0）

### A. 相册编辑（`com.miui.mediaeditor`）— 无需 Magisk 模块
| 功能 | Hook（`XC_MethodReplacement` → true / 原逻辑） | 备注 |
|---|---|---|
| 门禁解锁（开关 `enable_gate_bypass`，默认开） | CN 2.10.43.0.6：`rt.a.g()Z`、`ah0.b.g()Z`；GLOBAL 2.10.40.4.8：`mx.a.g()Z`、`r21.e.b()Z`(static) | 关闭开关时 `invokeOriginalMethod` 恢复原逻辑；不 hook `bx.t.a()/ft.s.a()`（传奇一瞬分支） |
| 小米账号 Cookie 注入 | `go.e.b()`（CN）/ `bs.e.b()`（GLOBAL）——STS Cookie 提供者；替换返回值 + 同步写回其进程内静态缓存字段 `b`（否则只有第一个请求生效）；原值回传 UI 作"系统账号捕获" | 与属性无关 |
| 未登录兜底 | `ga0.e.a(Context)`（CN）/ `be0.e.a(Context)`（GLOBAL）——上传前 `uploadFile: failed, not login` 硬检查；无系统账号且已配置 Cookie 时返回 Cookie 中的 `userId/cUserId`（否则 `demo`） | — |
| CTA 隐私协议 | **不强制**：正常弹窗用户自己同意 | — |
| 云控 allowlist / 服务端权益 | 客户端无法绕过 | 服务端判定 |

### B. 相机「徕卡一瞬」（`com.android.camera`）— 需 Magisk `CameraOnly` 变体提供 CN 相机 APK
| # | Hook（CN MiuiCamera 6.7.000280.0） | 签名 | 强制 | 作用 |
|---|---|---|---|---|
| H1 | `com.android.camera.features.mode.legendary.LegendaryEnter` | `support()Z`（instance） | `true` | 让「徕卡一瞬」模式（id `0x100`）出现在模式列表（模式表 `Lu3/a` 对每个 entry 调 `support()` 过滤） |
| H2 | `Te.c` | `W()Z`（static） | `true` | 相机全局 "isLeicaCustomize"（原实现读缓存属性）；15+ 处徕卡 UI/水印/变焦 UI 调用点，也是 H1 背后 `h5()` 的依赖 |
| H3 | obf. `L뾓…;`（19 字符） | `h5()Z`（instance） | `true` | 兜底（H1 无法解析时）；类名用 unicode escape 编码，解析失败仅记日志 |

- 相机 hook 同样无任何属性读写；关闭 `enable_gate_bypass` 时恢复原逻辑。
- scope.list：`com.miui.mediaeditor`、`com.miui.gallery`（占位，不装 hook）、`com.android.camera`。

### C. 安全/隐私
- Cookie 仅存模块 App 私有 SharedPreferences；Provider 按 Binder UID+包名白名单校验，且 **camera 调用者拿不到 Cookie**（provider 会剥离 cookie 字段）。
- 不伪造硬件认证 token、不绕过服务端权益/计费/设备白名单、不强制 CTA。
- 不修改系统属性/不替换 APK（相机 APK 由 Magisk CameraOnly 负责挂载）。

---

## 3. 使用方法（两条路径）

### 路径 A：只要相册编辑「二次增强」云端入口（**不需要 Magisk 模块**）
1. 安装 APK → LSPosed 管理器启用模块 → scope 勾选 `com.miui.mediaeditor`（`com.miui.gallery` 可选，仅占位）→ 重启。
2. 打开模块 App：登录/粘贴/捕获小米账号 Cookie → 保存。
3. **清除一次相册能力缓存**（相册会缓存能力探测结果到 SharedPreferences）：`设置→应用→相册→存储→清除缓存`（或 `adb shell pm clear com.miui.gallery`，会清相册本地设置，谨慎）。
4. **强制停止** `com.miui.mediaeditor`、`com.miui.gallery`，打开「相册 → 编辑」验证入口。
5. 生效日志（LSPosed 日志，前缀 `LeicaMomentLSP:`）：`predicate hook installed: ...`、`cookie hook installed: ...`、`injecting user cookie ...`、`account probe hook: ...` 等。

### 路径 B：还要相机「徕卡一瞬」拍摄（需要无属性 Magisk 变体）
1. 刷入 **Magisk v1.2.0 `CameraOnly`** 模块（`out/leica_moment_port/LeicaMoment_nezha_v1.2.0_CameraOnly.zip`）：只挂载国行 MiuiCamera 6.7 APK + 隐藏旧 oat + 一次性清相机数据；**不设置任何属性**（post-fs-data 为空操作）。
2. 本 LSPosed 模块 scope 增加 `com.android.camera` → 重启。
3. 相机启动后应出现「徕卡一瞬」模式入口与徕卡 UI（由 H1/H2 生效）；日志见 `camera hook installed: ...`。

---

## 4. 限制与风险（重要）

1. **相机硬件能力**：真正的徕卡风格处理（M9 CCD / M3 MONOPAN-50 / M10R）在 vendor camera HAL 管线中，APK 只发送 `com.xiaomi.sessionparams.legendMode` / `legendMode.capsInfo` / `getLegendBokehCapsInfo` 等请求。**Java hook 无法给 HAL 增加能力**：若设备 HAL 不支持，UI 可能出现但实际徕卡风格会缺失/回退（最大相机侧风险）。`LegendaryNativeCaptureBridge` 等类在 APK 中仅被引用、未定义（动态/插件/native），亦为风险项。
2. **服务端权益**：云端「二次增强」的点数/配额/设备白名单/硬件认证 token 均由小米服务端校验，客户端只能打开入口，**可能"入口出现但任务失败/排队失败"**。
3. **版本漂移**：hook 目标是 R8 混淆类名（`rt.a`/`ah0.b`/`mx.a`/`r21.e`/`Te.c` 等），mediaeditor/camera 小版本升级后可能变化 → 模块会记录未命中并安全跳过，不会崩溃目标 App。
4. **相册缓存**：Gallery 能力缓存需手动清一次（见路径 A 第 3 步）。
5. **无真机验证**：本环境无设备，全部为静态逆向 + 可复现构建验证；运行时行为需实测。
6. LSPosed 上游 release（v1.9.2）仅到 Android 14，Android 17 需维护分支/新版本；legacy 模块格式仍兼容。
7. 相机 APK 来自国行 ROM（商业资源），请自行从自有 ROM 提取，勿分发。

---

## 5. 验证状态（静态）

- 可复现构建：两次构建 sha256 一致（`a8526e06…7593`，45,668 B，vc=10/vn=2.4.0）。
- 签名：apksigner `Verifies`，v1+v2+v3（`--min-sdk-version 21` 校验）。
- APK：`classes.dex`（72,400 B）、`assets/xposed_init` → `HookEntry`、`META-INF/xposed/{module.prop,scope.list(含 com.android.camera)}`；`aapt2 dump badging` vc=10/vn=2.4.0、minSdk 29/targetSdk 35、INTERNET；Manifest 含 `LoginActivity`(exported=false)；dex 内 **0** 个 `de.robv` 类定义（仅类型引用，LSPosed 要求）。
- dex 中已无任何系统账号 Cookie 路径字样（系统账号捕获/填入捕获/cookie_mode/captured_cookie/hyperos_sec_common/登录系统小米账号/set_captured_cookie 均为 0）。
- v2.2.1 新增：WebView 错误处理方法（`onReceivedHttpError`/`onReceivedError`）、`i.mi.com/sts` 一次性自动恢复逻辑、直接打开 `https://i.mi.com/` 的登录/STS 入口；dex 中**已无**旧的无效应答 URL（`pass/serviceLogin?sid=i.mi.com` 与 `sts%3Fsign%3Dx` 均为 0）。
- 新增功能字符串已验证入 dex（检测 Cookie、serviceToken、3 个探测端点、Cookie 组成检查、2.2.0）；**未硬编码任何用户凭据**（源码与 dex 中均无粘贴过的 userId/cUserId/deviceId 字面量）；Cookie 值不写入日志/诊断文件。
- dex 中**不存在**任何属性伪装字符串：`ro.theme_customize`、`ro.miui.build.region`、`ro.theme_leica`、`ro.special_edition`、`SPOOF_PROPS`、`hookSystemProperties`、`resetprop`。
- Hook 目标均来自两国 smali 逐行证据：`docs/lsposed_hook_targets.md`（editor）、`docs/camera_hook_targets.md`（camera）。

---

## 6. 文件索引

| 路径 | 说明 |
|---|---|
| `out/leica_moment_port/lsposed/LeicaMoment_LSPosed_v2.4.0.apk`（源：`work/leica/lsposed/dist/`） | 最终 LSPosed APK（45,668 B，sha256 `a8526e06…7593`） |
| `work/leica/lsposed/app/` | 模块源码（Java + manifest + xposed 元数据） |
| `work/leica/lsposed/app/README_APP.md` | 用户指南（中文，含两条使用路径/排错） |
| `work/leica/lsposed/build.sh` / `README_BUILD.md` | 无 Gradle 可复现构建 |
| `work/leica/docs/lsposed_hook_targets.md` | 编辑器 hook 逆向报告（smali 证据） |
| `work/leica/docs/camera_hook_targets.md` | 相机 hook 逆向报告（11 个 CN camera dex 证据） |
| `work/leica/lsposed/analysis/{hook_targets,camera_hook_targets}.json` | 机器可读 hook 目标 |
| `work/leica/rust_check/` | Rust 组件证据 |
