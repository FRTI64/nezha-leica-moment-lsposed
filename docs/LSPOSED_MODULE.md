# LSPosed 模块 v2.1.0：**不伪装任何系统属性**的小米账号 Cookie + 徕卡一瞬/二次增强解锁

> 交付物：`out/leica_moment_port/lsposed/LeicaMoment_LSPosed_v2.1.0.apk`（源：`work/leica/lsposed/dist/LeicaMoment_LSPosed_v2.1.0.apk`；legacy Xposed 模块，LSPosed 可用）
> 版本：`versionCode 5 / versionName 2.1.0`，29,284 B，sha256 `000021d9517678e9b787df9c0c8e849a983f526156a55cb6ba487d84a32b3a25`（两次构建字节一致）
> 目标：小米 17 Ultra（`nezha` / `nezha_global`），HyperOS 4 / Android 17；`com.miui.mediaeditor`（相册-编辑）、可选 `com.android.camera`（配 Magisk CameraOnly）、`com.miui.gallery`（占位）
>
> ✅ **本版本完全不伪装系统属性**：不做 `resetprop`、不 hook `android.os.SystemProperties`、不修改任何 `ro.*` 值。改为**直接 hook App 内部的门禁判定方法**返回 `true`（只影响被 hook 的两个进程）。仍为静态分析产物，**未真机运行验证**。

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

- 可复现构建：两次构建 sha256 一致（`000021d9…3a25`，29,284 B）。
- 签名：apksigner `Verifies`，v1+v2+v3（`--min-sdk-version 21` 校验）。
- APK：`classes.dex`（38,420 B）、`assets/xposed_init` → `HookEntry`、`META-INF/xposed/{module.prop,scope.list(含 com.android.camera)}`；`aapt2 dump badging` vc=5/vn=2.1.0、minSdk 29/targetSdk 35、INTERNET、launchable Activity；dex 内 **0** 个 `de.robv` 类定义（仅类型引用，LSPosed 要求）。
- dex 中**不存在**任何属性伪装字符串：`ro.theme_customize`、`ro.miui.build.region`、`ro.theme_leica`、`ro.special_edition`、`SPOOF_PROPS`、`hookSystemProperties`、`resetprop`。
- Hook 目标均来自两国 smali 逐行证据：`docs/lsposed_hook_targets.md`（editor）、`docs/camera_hook_targets.md`（camera）。

---

## 6. 文件索引

| 路径 | 说明 |
|---|---|
| `out/leica_moment_port/lsposed/LeicaMoment_LSPosed_v2.1.0.apk`（源：`work/leica/lsposed/dist/`） | 最终 LSPosed APK |
| `work/leica/lsposed/app/` | 模块源码（Java + manifest + xposed 元数据） |
| `work/leica/lsposed/app/README_APP.md` | 用户指南（中文，含两条使用路径/排错） |
| `work/leica/lsposed/build.sh` / `README_BUILD.md` | 无 Gradle 可复现构建 |
| `work/leica/docs/lsposed_hook_targets.md` | 编辑器 hook 逆向报告（smali 证据） |
| `work/leica/docs/camera_hook_targets.md` | 相机 hook 逆向报告（11 个 CN camera dex 证据） |
| `work/leica/lsposed/analysis/{hook_targets,camera_hook_targets}.json` | 机器可读 hook 目标 |
| `work/leica/rust_check/` | Rust 组件证据 |
