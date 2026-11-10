# nezha-leica-moment-lsposed

**Xiaomi 17 Ultra（`nezha`）「徕卡一瞬 / 相册编辑·二次增强」LSPosed 模块 v2.1.0 —— 零属性伪装**

> ✅ 不 resetprop、不 hook `SystemProperties`、不修改任何 `ro.*` 属性：改为**直接 hook App 内部判定方法**返回 true（仅影响被 hook 进程）。
> 同时保留小米账号 Cookie 登录/注入、未登录兜底；CTA 不强制、不伪造硬件认证、不绕过服务端权益。

- 目标：`com.miui.mediaeditor`（相册-编辑，国际版 2.10.40.4.8-global / 国行 2.10.43.0.6）；可选 `com.android.camera`（配 Magisk CameraOnly 提供国行 MiuiCamera 6.7 APK）。
- 预构建 APK：`prebuilt/LeicaMoment_LSPosed_v2.1.0.apk`（29,284 B，sha256 `000021d9…3a25`，versionCode 5）。
- 未真机验证；仅供个人设备本地研究与自用修改。

---

## 1. 功能与 Hook 清单

### A) 相册编辑「二次增强」入口（无需 Magisk 模块）
| 功能 | Hook | 说明 |
|---|---|---|
| 门禁解锁（开关 `enable_gate_bypass`，默认开） | CN 2.10.43：`rt.a.g()Z`、`ah0.b.g()Z`；GLOBAL 2.10.40：`mx.a.g()Z`、`r21.e.b()Z`(static) | 直接返回 true；关闭开关 `invokeOriginalMethod` 恢复；不 hook 传奇一瞬分支 |
| Cookie 注入 | `go.e.b()`(CN) / `bs.e.b()`(GLOBAL) STS Cookie 提供者 | 替换返回值 + 写回其进程内静态缓存字段 `b`；原值回传 UI 供复用 |
| 未登录兜底 | `ga0.e.a(Context)` / `be0.e.a(Context)` | 绕过上传前 `uploadFile: failed, not login` 硬检查（用 Cookie 中 userId/cUserId 或 `demo`） |
| CTA | 不 hook | 正常隐私弹窗由用户自己同意 |

### B) 相机「徕卡一瞬」（需 Magisk CameraOnly 提供 CN MiuiCamera 6.7.000280.0）
| # | Hook | 签名 | 强制 |
|---|---|---|---|
| H1 | `com.android.camera.features.mode.legendary.LegendaryEnter` | `support()Z`（instance） | true（模式 id 0x100 出现） |
| H2 | `Te.c` | `W()Z`（static） | true（isLeicaCustomize：15+ 徕卡 UI/水印调用点） |
| H3 | obf. `L뾓…;`（19 字符） | `h5()Z`（instance） | true（兜底，解析失败安全跳过） |

scope.list：`com.miui.mediaeditor`、`com.miui.gallery`（占位无 hook）、`com.android.camera`。

---

## 2. 仓库结构

```
.
├── app/                    模块源码（Manifest + 4 Java + Xposed 元数据 + README_APP.md）
├── build.sh                无 Gradle 可复现构建（aapt2 → javac → d8 → zipalign → apksigner）
├── README_BUILD.md         工具链与构建说明（Android SDK cmdline-tools + Xposed API 82）
├── docs/
│   ├── LSPOSED_MODULE.md       模块设计/原理/证据/限制（v2.1.0）
│   ├── hook_targets.md         编辑器 hook 目标逆向（两国 mediaeditor，smali 证据）
│   └── camera_hook_targets.md  相机无属性 hook 逆向（CN MiuiCamera 6.7，11 dex）
├── example/hello/          最小 LSPosed 模块示例（构建管线自检）
└── prebuilt/               预构建 APK v2.1.0 + SHA256SUMS
```

---

## 3. 构建

需要 JDK 17+、Android SDK cmdline-tools（`platforms;android-35`、`build-tools;35.0.0`）、Xposed API 82 jar（`xposed-api-82.jar`），无需 Gradle/Android Studio：

```bash
# 1) 安装 SDK 到 ./toolchain（避免写 $HOME）
# 2) 放 Xposed API 82 jar 到 ./toolchain/libs/xposed-api-82.jar
VERSION_CODE=5 VERSION_NAME=2.1.0 ./build.sh app dist/LeicaMoment_LSPosed_v2.1.0.apk
```

详见 [README_BUILD.md](README_BUILD.md)。

## 4. 安装与使用（两条路径）

### A. 只要相册编辑「二次增强」入口（无需 Magisk 模块）
1. `adb install -r prebuilt/LeicaMoment_LSPosed_v2.1.0.apk`；
2. LSPosed 管理器 → 启用 → scope 勾选 `com.miui.mediaeditor`（`com.miui.gallery` 可选）→ 重启；
3. 模块 App 内登录/粘贴/捕获小米账号 Cookie → 保存；
4. **清除一次相册能力缓存**（相册缓存探测结果）：`设置→应用→相册→存储→清除缓存` 或 `adb shell pm clear com.miui.gallery`（谨慎）；
5. 强制停止 `com.miui.mediaeditor`/`com.miui.gallery` → 打开「相册 → 编辑」验证入口。

### B. 还要相机「徕卡一瞬」拍摄
1. 刷入 Magisk **CameraOnly** 变体（见姊妹仓库 `nezha-leica-moment-magisk`）：只挂载国行 MiuiCamera APK，零属性改动；
2. 本模块 scope 增加 `com.android.camera` → 重启；
3. 相机出现「徕卡一瞬」模式入口与徕卡 UI（H1/H2）。

## 5. 验证状态

- 可复现构建：两次 sha256 一致（`000021d9517678e9b787df9c0c8e849a983f526156a55cb6ba487d84a32b3a25`，29,284 B，vc=5/vn=2.1.0）。
- apksigner `Verifies`（v1+v2+v3，`--min-sdk-version 21`）；`aapt2 dump badging` 正常；dex 内 0 个 `de.robv` 类定义。
- dex 中**不存在** `ro.theme_customize`/`ro.miui.build.region`/`ro.theme_leica`/`ro.special_edition`/`SPOOF_PROPS`/`hookSystemProperties`/`resetprop`（零属性伪装证据）。
- Hook 目标均有两版 smali 证据（`docs/`）。

## 6. 已知限制

- 相机真正的徕卡风格处理在 vendor camera HAL（M9 CCD/M3 MONOPAN/M10R）；Java hook 只能让 UI 出现，若 HAL 不暴露 `legendMode` caps 则实际效果可能缺失/回退（主要风险）。
- 云服务端权益/配额/设备白名单/硬件认证 token 客户端不可控——入口可能可见但任务失败。
- Hook 目标是 R8 混淆类名，App 版本升级可能失效（模块会安全跳过并记录日志）。
- 相册能力缓存需手动清一次；无真机验证。
- LSPosed 上游 v1.9.2 仅支持到 Android 14，Android 17 需维护分支/新版本（legacy 模块格式兼容）。

## 7. 相关项目

- **Magisk 文档 + 相机 CameraOnly 模块源码/分析**：<https://github.com/FRTI64/nezha-leica-moment-magisk>

## 8. 免责声明

仅包含自研 Java/shell 源码与逆向文档，不含官方 APK/ROM/商业资源；不提供计费/权益/设备白名单/硬件认证绕过；与 Xiaomi/Leica 无关；使用风险自负。
