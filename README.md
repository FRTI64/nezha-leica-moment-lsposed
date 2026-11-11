# nezha-leica-moment-lsposed

**Xiaomi 17 Ultra（`nezha`）「徕卡一瞬 / 相册编辑·二次增强」LSPosed 模块 v2.6.1 —— 零属性伪装**

> ✅ 不 resetprop、不 hook `SystemProperties`、不修改任何 `ro.*` 属性：改为**直接 hook App 内部判定方法**返回 true（仅影响被 hook 进程）。
> 同时保留小米账号 Cookie 登录/注入、未登录兜底；**v2.4.0 按用户要求彻底移除系统账号 Cookie 路径**（Cookie 来源仅限手动粘贴/全屏网页登录），并新增**网页会话自检**；v2.3.0 的全屏登录与 UA 自适应体检保留；v2.2.2 修复 Cookie 体检误报（真实鉴权信号端点 + UA 自适应）；v2.2.1 修复 STS 网页错误（`net::ERR_HTTP_RESPONSE_CODE_FAILURE`，旧版 sign=x 占位符问题）；v2.2.0 新增 **Cookie 体检（只读 GET 探测）**、**WebView STS 助手（补 serviceToken）** 与**卡片式新 UI**。CTA 不强制、不伪造硬件认证、不绕过服务端权益。

- 目标：`com.miui.mediaeditor`（相册-编辑，国际版 2.10.40.4.8-global / 国行 2.10.43.0.6）；可选 `com.android.camera`（配 Magisk CameraOnly 提供国行 MiuiCamera 6.7 APK）。
- 预构建 APK：`prebuilt/LeicaMoment_LSPosed_v2.6.1.apk`（66,148 B，sha256 `44e441e1…2945`，versionCode 19）。
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
| Cookie 体检（v2.2.0） | 后台线程 GET 只读探测（不跟随重定向、8s 超时、8KB 截断）：`i.mi.com/status/lite/setting`、`gallery.i.mi.com`、`i.mi.com/gallery/public/resource/info` | 分色判定 2xx/登录重定向/401-403/网络错误；**不触发 legendV2/create 等可能计费的 POST**；非权益验证 |
| STS 助手（v2.2.0） | 登录后「去 i.mi.com 完成 STS」→ 读取并合并 account.xiaomi.com/i.mi.com/gallery.i.mi.com/api.open.ai.xiaomi.com/xiaomi.com Cookie | Cookie 组成检查（彩色键位）：缺 `serviceToken` 时醒目提示（Web Cookie ≠ STS Cookie） |
| UI（v2.2.0） | 全程序化卡片式界面、深浅色自适应、状态胶囊、分区（状态总览/账号与 Cookie/Cookie 体检/功能开关/说明与限制）、WebView 与帮助可折叠 | 无 XML 资源 |

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

## 5.13 v2.6.1 新增：云端报文捕获（create 请求 JSON + 服务端响应 body）

create 仍返回 HTTP 400 / errorCode 20090002（INVALID_INPUT_PARAM_ERROR）。v2.6.1 在 mediaeditor 进程捕获：
- `CreateLegendV2TaskParamsJsonAdapter.toJson*` 返回的 **create 请求 JSON**（`legend_create_body=`）；
- 混淆 OkHttp ResponseBody（`b11.f0`）`String` getter 的**服务端响应 body**（`cloud_resp_body=`，脱敏/截断 600 字符）。
两者经 Provider `append_cloud_capture` 本地保存最近 8 条，模块 ③ 新增「云端报文捕获（最近8条）」+「刷新云端报文」，并入「复制诊断信息」；不记录/不上传令牌与 header。

## 5.12 v2.6.0 新增：hook 侧同步 CN/Global 相机 MakerNote

CN 相机 6.7 的 MakerNote 标签表（`Bf.g`）注册了 5 个 Moment 标签（0x30~0x34, type=4）；Global 相机 6.6（`Ig.g`）只注册 5 个 HdrDisplay 标签，保存时会丢弃不认识的 Moment 标签。v2.6.0 在相机进程向标签表注入这 5 个 Moment 标签描述（`sync_cn_camera_makernote`，默认开），让 Global 相机保存时保留 HAL 写入的 Moment 元数据；诊断新增 `camera_makernote=CN|GLOBAL|none`。不替换 APK、不改属性。

## 5.11 v2.5.3 更新：真机首次打通云端（补全 create 参数 + 响应 body 诊断）

真机（Global ROM）实测：解析成功，uploadFile 成功（Cookie/鉴权/上传全通），真实云基址 `https://sgp-ai-photo.engine.intl.miui.com`；但 `CreateLegendV2TaskParams` 中 aperture/focusX/focusY=null、oaid 空 → create 返回 HTTP 400 / errorCode 439。v2.5.3：
- hook `CreateLegendV2TaskParams<init>` 自动补全空参数（aperture=1.67f、focusX=2048、focusY=1536、oaid=伪 UUID），开关 `force_legend_cloud_params`；
- 云端轨迹对 `aiPhotoCloud/legendV2` 响应追加非消费式 body 片段（`peekBody(1024)` 脱敏截断），可看到服务端 400 的具体原因。

## 5.10 v2.5.2 修复：GLOBAL mediaeditor 兼容

v2.5.1 的 legend 回落 hook 只匹配 CN mediaeditor 混淆名（`lv.j0`），在 Global ROM（MIMediaEditorGlobal）上静默跳过 → 仍「图片解析失败」。v2.5.2 改为通用安装器：
- 依次尝试 CN（`lv.j0→lv.d0, lv.i0`）与 GLOBAL（`hz.q0→hz.k0, hz.p0`），按形状 hook 静态解析方法并返回构造的 `V2/M3/TRUE` 对象；
- 第二道保险：hook MakerNote getter（CN `pc.g.a(String)` / GLOBAL `mg.g.a(String)`），对缺失的 `MomentVersion/MomentType/MomentSubMode/MomentPro/...` 合成 `2/9/2/1/4`；
- 诊断新增 `legend_fallback_target=CN|GLOBAL|none`。

## 5.9 v2.5.1 新增：legend 版本回落注入（选项 B）

真机确认照片缺 Xiaomi 一瞬 MakerNote（`LegendaryVersionResolver: version/momentType/subMode/isPro/depthMapVersion=null`），decodeImage 走 `legendInfo is null` → 「图片解析失败」，云请求不会发出。v2.5.1 在 mediaeditor 进程 hook `lv.j0.a(Lpc/b;)Llv/d0;` 与 `lv.j0.b(String,Lpc/b;)Llv/d0;`，返回构造的 `lv.d0(V2, M3, TRUE, f)`，使 `legendInfo missing, allow fallback for V2 M3 bokeh` 兜底分支成立，照片可继续走 cleaned upload/云端请求。默认开启（`force_legend_fallback`），可在 ④ 功能开关关闭；全部反射解析 fail-safe。风险：可能产生一次云请求/消耗额度，服务端仍可能拒绝。

## 5.8 v2.5.0 新增：强制启动二次增强（排障）

真机相册的「二次增强」入口不显示，而 mediaeditor 的 `LegendaryActivity` 是 `exported=true` 且从 `Intent.data` 取图片 Uri。模块 App 新增「②.5 强制启动二次增强（排障）」：
- 「选择照片并启动」（ACTION_OPEN_DOCUMENT → 显式 Intent 启动 LegendaryActivity + URI 读授权，失败回退组件变体）；
- 「用相册最新照片启动」（申请媒体读取权限 → MediaStore 最新图片 → 同样启动）；
- 「复制 adb 启动命令」（`am start -n ... -d <uri> --grant-read-uri-permission` 兜底）。
配合 v2.4.3「云端请求轨迹」即可看到真实 `legendV2/create` 的 HTTP 结果。

## 5.7 v2.4.3 新增：设备端云端请求轨迹（排障）

开发环境的出口 IP 被小米风控拦截（同一 Cookie 在用户设备 micloud 200，在沙箱 i.mi.com/sts 与 micloud 均 401），无法在本地完成云端"通过"验证。因此在 mediaeditor 的云请求拦截器（CN `nm.f` / GLOBAL `jq.g`）加 after-hook：记录 App 实际请求的 `method + host + encodedPath`（**不含 query/签名**）、HTTP 状态码、是否带 Cookie（**不记录任何值/请求体/响应体**），环形保存最近 12 条，并暴露到模块 UI「云端请求轨迹」与「复制诊断信息」。用法：安装 v2.4.3 → 打开相册编辑并触发一次「二次增强」→ 回模块点「刷新云端轨迹」或「复制诊断信息」。用于定位真实 AI 云基址与鉴权结果。

## 5.6 v2.4.2 更新：体检判定修正（micloud 200 = 鉴权通过）

真机诊断显示：网页自检 200；micloud `type=1/2` 在 Web UA 下返回 **HTTP 200**（v2.4.1 的 Cookie 合并优先级修复已解决 401）。据此调整：
- **任何 2xx 且无登录标识的 micloud 响应 = 绿色「鉴权通过」**；业务/缺参错误只附加备注，不再降级 WARN；
- AI 云平台探测行改为**参考**（基址由远端 CloudConfig `AIConfig.url_config` 下发，写死 URL 可能 404）：404/405 判为「路径/基址不匹配（不代表 Cookie 无效）」且**不参与**鉴权结论；
- 聚合：两条 micloud 均 2xx → 「Cookie 已被小米云鉴权接受 → 鉴权通过；请直接实测二次增强（能否出图取决于服务端权益/设备白名单/硬件认证 token）」；任一 micloud 401/403 → 红色被拒；纯网络问题 → 无法判定。

## 5.5 v2.4.1 更新：真实目标 AI 云平台探测 + Cookie 合并优先级修复

- 新增只读 POST 探测「AI 云平台（真实目标 API）」：`api.open.ai.xiaomi.com/aiPhotoCloud/api/legendV2/list`（body `{}`，不扣费/不创建任务），UA 自适应（Web/桌面 Chrome/MiuiGallery）；这比 i.mi.com micloud 探测更贴近「二次增强」实际鉴权。
- **Cookie 合并优先级修复**：`i.mi.com > api.open.ai.xiaomi.com > gallery.i.mi.com > account.xiaomi.com > xiaomi.com`，避免 account.xiaomi.com 的同名 serviceToken 覆盖云端 token（可能是此前 401 的原因）；组成检查/诊断显示每键来源域名（仅键名）。
- 「复制诊断信息」按钮（版本、端点 HTTP 码、UA 类型、自检结果、Cookie 键名/长度/来源域名，绝不含令牌值）。
- 结论措辞：micloud 401 但 AI 云平台未拒绝 → 实际二次增强可能仍可用（以实测为准）；两者均 401 → 网页 Cookie 不被云鉴权接受（服务端行为，不使用系统账号 Cookie 则客户端无法修复）。

## 5.4 v2.4.0 更新：移除系统账号 Cookie（用户要求）+ 网页会话自检

- **彻底移除系统账号 Cookie 路径**：删除系统账号捕获卡片、「登录系统小米账号」、「填入捕获的 Cookie」、Cookie 来源模式及回退；HookEntry 不再捕获/上报 App 原生 STS Cookie（无用户 Cookie 时原逻辑透传、不读取不记录）。
- **Cookie 来源仅限**：用户手动粘贴或全屏网页登录导出。
- **网页会话自检**：LoginActivity 内 `evaluateJavascript` 带凭据 fetch `i.mi.com/status/lite/setting?type=1`，经 `@JavascriptInterface` 回传 HTTP 码与脱敏摘要；「完成」时自动执行一次。用于区分“网页未登录/STS 未完成（401）”与“网页会话有效但 Cookie 被 App 云 API 拒绝”。
- 体检：UA 自适应 + Referer/Accept-Language；若自检 200 而体检 401，会明确提示这是网页 Cookie 与 App 云 API 鉴权链路差异（服务端行为，客户端无法修复）。

## 5.3 v2.3.0 更新：全屏登录 + Cookie 来源模式

- **全屏登录**：新增 `LoginActivity`（顶栏 返回/刷新/完成、进度条、错误提示、返回键、`i.mi.com/sts` 一次性自动恢复），替代原先内嵌小 WebView；「完成」自动合并 5 个小米域 Cookie 并回填。
- **Cookie 来源模式**：`auto`（默认，手动→系统捕获）/`manual`/`captured`；**Hook 注入按同一优先级**，系统账号已登录的设备可**零操作自动注入 App 原生 STS Cookie**（hyperos_sec_common_*/serviceToken）。
- **UA 自适应体检**：`pass_ua=web` 的 Cookie 先按 Web UA 探测，401 后自动换 MiuiGallery UA 重试；若网页 Cookie 被拒而系统捕获 Cookie 存在，会提示切换来源。
- 推荐流程：系统设置登录小米账号 → 打开相册-编辑一次触发 App 取 STS Cookie（自动捕获）→ 来源「自动/系统捕获」→ 保存 → 清相册缓存 → 强制停止 mediaeditor/相册 → 实测。WebView 仅作备用。

## 5.2 v2.2.2 修复：Cookie 体检误报

旧版体检使用 `gallery.i.mi.com`（DNS 不解析）与 `/gallery/public/resource/info`（无 Cookie 也返回 200 + 缺参错误，无鉴权信号），并把网络异常/参数错误当作认证失败，导致有效 Cookie（已含 `serviceToken`）被判"可能无效"。v2.2.2 改用 `https://i.mi.com/status/lite/setting?type=1|2`（未登录/无效 Cookie 明确返回 HTTP 401 + `{"R":401,...serviceLogin...}`），三级判定：绿色=未被要求重新登录；黄色=网络/参数问题（**不判定 Cookie**）；红色=被拒绝/重定向登录。

## 5.1 v2.2.1 修复：STS 网页打不开（`net::ERR_HTTP_RESPONSE_CODE_FAILURE`）

旧版「打开登录页」使用手工拼接的 passport URL（`callback=...i.mi.com/sts?sign=x...`），`sign=x` 只是从 ROM 复制的占位符；登录后 passport 带**无效 sign** 跳回 `i.mi.com/sts`，服务端返回 HTTP 错误页，WebView 显示该错误。v2.2.1 起：

- 「打开 i.mi.com（登录+STS）」直接加载 `https://i.mi.com/`，由小米站点发起带有效 sign 的登录/STS 跳转链；
- 新增 WebView `onReceivedHttpError` / `onReceivedError` 友好提示；
- 当 `i.mi.com/sts` 回调页报错时**一次性自动恢复**：自动回到 i.mi.com 重走登录流程；
- 「打开小米账号页（备用）」打开 `account.xiaomi.com`。

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
