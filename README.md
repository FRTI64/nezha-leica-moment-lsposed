# nezha-leica-moment-lsposed

**Xiaomi 17 Ultra（`nezha`）「徕卡一瞬 / 相册编辑·二次增强」LSPosed 模块源码**
—— 进程级门禁伪装 + 小米账号 Cookie 登录/注入，让国际版 ROM 的 `com.miui.mediaeditor`（相册-编辑）显示并放行云端「二次增强」入口。

> ⚠️ 仅供个人设备本地研究与自用修改；**未在真机运行验证**；不伪造硬件认证、不绕过服务端权益/计费；不强制通过隐私 CTA。Xiaomi/Leica/HyperOS 商标与资源版权归权利人所有。

---

## 1. 模块能力

| 能力 | 实现 | 默认 |
|---|---|---|
| 绕过编辑器入口门禁 | 在 `com.miui.mediaeditor` 进程内 hook `android.os.SystemProperties.get/getBoolean/getInt`，伪装 `ro.miui.build.region=cn`、`ro.theme_customize=LCC`、`ro.theme_leica=true`、`ro.boot.product.theme_customize=P1_Leica`、`ro.special_edition=true`（**不改系统全局属性**，仅此进程） | ✅ |
| 注入小米账号 Cookie | hook App 原生 STS Cookie 提供者：国行 `go.e.b()` / 国际版 `bs.e.b()`（`AccountManager` 类型 `com.xiaomi` → `getAuthToken("hyperos_sec_common")` → Bundle `stsCookies`）；替换返回值并同步写回其进程内静态缓存字段 | ✅ |
| 登录小米账号 | ① WebView 登录抓取 Cookie；② 一键调起系统小米账号登录（`AccountManager.addAccount("com.xiaomi","passportapi")`）；③ 已登录时 App 原生取到的 STS Cookie 自动回传、可回填复用 | UI 功能 |
| 未登录兜底 | 上传步骤有客户端硬检查 `uploadFile: failed, not login`（`AccountUtil`：国行 `ga0.e.a(Context)` / 国际版 `be0.e.a(Context)`）；无系统账号且已配置 Cookie 时返回从 Cookie 的 `userId/cUserId` 解析的账号标识（兜底 `demo`） | ✅（仅无账号时生效） |
| CTA 隐私协议 | **不强制**：入口可见性只由属性判定；CTA 对话框由 `LegendaryActivity` 正常弹出、用户亲自同意 | ❌ |
| 云端签名 | 无需 hook：`signature` 由客户端内置 `appSecret` + body MD5 + 时间戳计算，与账号/Cookie 无关 | — |

云端权益（点数/配额/设备白名单）与硬件认证 token **在服务端校验，客户端无法绕过**；预期可能出现"入口出现但任务失败/排队失败"。

---

## 2. 仓库结构

```
.
├── app/                        模块源码（AndroidManifest + Java + Xposed 元数据）
│   ├── AndroidManifest.xml
│   ├── src/com/nezha/leica/lsposed/
│   │   ├── HookEntry.java          Xposed 入口：SystemProperties 伪装 / Cookie 注入 / 登录探测
│   │   ├── MainActivity.java       配置 UI：WebView 登录、手动粘贴、系统账号登录、开关
│   │   ├── CookieConfigProvider.java  供被 hook 进程读取配置/回传捕获 Cookie（按 uid+包名白名单）
│   │   ├── ModuleConfig.java
│   │   └── README_APP.md           用户指南（中文）
│   ├── assets/xposed_init          → com.nezha.leica.lsposed.HookEntry
│   └── META-INF/xposed/{module.prop,scope.list}
├── build.sh                    无 Gradle 构建脚本（aapt2 → javac → d8 → zipalign → apksigner，可复现）
├── README_BUILD.md             工具链/构建说明（Android SDK cmdline-tools + Xposed API 82）
├── docs/
│   ├── LSPOSED_MODULE.md       模块设计/原理/Hook 证据/限制完整说明
│   └── lsposed_hook_targets.md 两国 mediaeditor 的 hook 目标逆向报告（smali 证据）
├── example/hello/             最小 LSPosed 模块示例（构建管线自检）
└── prebuilt/                  预构建 APK（v1.1.0）+ SHA256SUMS
```

---

## 3. 构建

需要：JDK 17+、Android SDK cmdline-tools（`platforms;android-35`、`build-tools;35.0.0`）、Xposed API 82 jar（`xposed-api-82.jar`）。**无需 Gradle / Android Studio**。

```bash
# 1) 安装 SDK（示例：放到 ./toolchain，避免写 $HOME）
#    下载 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
#    sdkmanager --sdk_root=./toolchain "platforms;android-35" "build-tools;35.0.0"
# 2) 放 Xposed API 82 jar 到 ./toolchain/libs/xposed-api-82.jar
# 3) 构建（build.sh 默认从 ./toolchain 定位 android.jar/build-tools/xposed jar）
cd lsposed-repo
VERSION_CODE=2 VERSION_NAME=1.1.0 ./build.sh app dist/LeicaMoment_LSPosed_v1.1.0.apk
```

详细步骤/校验命令/排错见 [README_BUILD.md](README_BUILD.md)。

## 4. 安装与使用

1. 设备：Xiaomi 17 Ultra（nezha），已装 Magisk + LSPosed（Zygisk/Riru），系统需支持 legacy Xposed API。
2. `adb install -r prebuilt/LeicaMoment_LSPosed_v1.1.0.apk`（或文件管理器安装）。
3. LSPosed 管理器 → 模块 → 启用「徕卡一瞬 LSPosed」→ 作用域勾选 `com.miui.mediaeditor`（可选 `com.miui.gallery`）→ 重启一次。
4. 打开模块 App：登录/粘贴/捕获 Cookie，按需打开开关并保存。
5. **强制停止** `com.miui.mediaeditor`（建议连同 `com.miui.gallery`）后重开相册 → 编辑，验证「徕卡一瞬/二次增强」入口。
6. 生效日志：LSPosed 日志中前缀 `LeicaMomentLSP:`（如 `cookie hook installed: go.e.b()` / `bs.e.b()`、`account probe hook: ...`）。

完整用户指南：[app/README_APP.md](app/README_APP.md)

## 5. 验证状态

- APK 可复现构建（两次构建 sha256 一致：`8bf6c96e9024067496cb06a45547258232fa4621c6395c48fed94152a27a0f0f`，29,284 B）。
- apksigner：`Verifies`，v1+v2+v3 方案齐全（`--min-sdk-version 21`）；`aapt2 dump badging` 包名/版本/minSdk 29/targetSdk 35/INTERNET/launchable Activity 正确。
- dex 内不打包 `de.robv.android.xposed` API 类（LSPosed 要求）；`assets/xposed_init` → `HookEntry`。
- Hook 目标均来自两国 smali 逐行证据（见 [docs/lsposed_hook_targets.md](docs/lsposed_hook_targets.md)），代码以"目标列表 + try/catch"实现，未知版本安全跳过。
- **未做真机验证**：运行时 hook 生效、服务端是否接受注入 Cookie 无法离线验证。

## 6. 已知限制

- 云端权益/硬件认证在服务端；模块不提供绕过，云端任务可能失败。
- WebView 抓取的 Cookie 与原生 STS Cookie 不一定等价，优先用"系统账号捕获"。
- 修改 Cookie/开关后必须强制停止 `com.miui.mediaeditor` 进程（拦截器有进程内静态缓存）。
- 不同 `com.miui.mediaeditor` 小版本混淆类名可能变化；模块会记录 `cookie target not present ...` 并跳过。
- LSPosed 上游 release（v1.9.2）仅支持到 Android 14；Android 17 设备需使用维护分支/新版本（legacy 模块格式仍兼容）。

## 7. 相关项目

- 同功能的 **Magisk 模块文档 + 源码**（相机 APK 替换 + 属性层）与完整逆向分析：<https://github.com/FRTI64/nezha-leica-moment-magisk>

## 8. 免责声明

本仓库仅包含自研 Java/shell 源码与逆向分析文档，不包含任何官方 APK/ROM/商业资源；不提供计费/权益/设备白名单/硬件认证绕过；与 Xiaomi/Leica 无关；使用风险自负。
