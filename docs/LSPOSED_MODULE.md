# LSPosed 模块 v2.7.5：**不伪装任何系统属性**的小米账号 Cookie（含体检/STS 助手）+ 徕卡一瞬/二次增强解锁

> 交付物：`out/leica_moment_port/lsposed/LeicaMoment_LSPosed_v2.1.0.apk`（源：`work/leica/lsposed/dist/LeicaMoment_LSPosed_v2.1.0.apk`；legacy Xposed 模块，LSPosed 可用）
> 版本：`versionCode 26 / versionName 2.7.5`，78,436 B，sha256 `3eebe9ad9cff0110288bf17a829002d994443617ace2782950e66e4d04d454c1`（两次构建字节一致）
> 目标：小米 17 Ultra（`nezha` / `nezha_global`），HyperOS 4 / Android 17；`com.miui.mediaeditor`（相册-编辑）、可选 `com.android.camera`（配 Magisk CameraOnly）、`com.miui.gallery`（占位）
>
> **v2.7.3 新增（HTTP 全链路追踪 + 原生 Cookie 键名）**：v2.7.2 区域模拟后云配置下发 CN 侧域名 `https://avatar-ai.sec.miui.com`，legendV2 返回 **401**（关闭 Cookie 注入、系统小米账号已登录仍 401）。v2.7.3 新增：
> ① hook 混淆 OkHttp Response（`b11.e0`）构造器，按 URL 过滤输出 `http_trace <code> <url> headers={www-authenticate/x-*/server/content-type/...}`（过滤 set-cookie/authorization，绝不记录凭据值），用于看 hardwareNonce/hardwareAuthToken/upload/create 的真实状态码与鉴权挑战头；
> ② Cookie provider hook 在注入关闭时输出 `native cookie: length=<n> keys=<键名>`（仅键名/长度），判断系统账号 Cookie 是否含 sec/security 类 token。

> **v2.7.2 新增（运行时 SystemProperties 区域/国家 hook）**：v2.7.1 只翻转了云配置 isGlobal 本地标志，`cloudConfigProductUrl:` 仍为 intl。v2.7.2 在 mediaeditor 进程 hook `android.os.SystemProperties.get(String[,String])`：记录所有 region/country/locale 属性读取（`region prop: name=value`），并把区域类属性在**进程内**改写为 CN（`ro.miui.build.region`→cn、`ro.miui.region` 等→CN），以促使云配置按 CN 区域返回基址；**不改系统属性、不落盘**。仍受 `simulate_cn_region` 开关控制（默认开）；建议先 `pm clear com.miui.mediaeditor` 强制重取云配置后再测。

> **v2.7.1 新增（模拟 CN 区域获取真实 CN 云基址）**：v2.7.0 硬编码 `api.open.ai.xiaomi.com` 的 legendV2 路径 404，说明 CN AI 照片云基址由 **CN 区域云配置**下发。核查 smali：GLOBAL 云配置工具 `jq.d.a` 读取 `r21.c.b`（isGlobal），CN 对应 `nm.c.a` 读取 `gz0.b.b`。v2.7.1 在 mediaeditor 进程把该静态布尔置为 `false`（并在 `jq.d.a`/`nm.c.a` beforeHook 中反复置位），使云配置按 CN 区域下发真正的 CN 基址；开关 `simulate_cn_region`（默认开），诊断新增 `cn_region=simulated|off|none`；`force_cn_cloud_endpoint` 改为默认关（硬编码 URL 404 的 A/B 对照保留）。可用 logcat 的 `cloudConfigProductUrl:` 验证是否变成 CN 域名。

### 最终结论（真机实测，v2.7.5）

**已打通（真实设备验证）**
1. **云端鉴权打通**：`simulate_cn_region=true` + 手动 Cookie（含 `hyperos_sec_common` 安全 token）+ 运行时区域属性 hook → 云配置下发 CN 侧域名 `https://avatar-ai.sec.miui.com`，`uploadFile`、`createLegendTask success, isV2=true` 均成功（不再 401/439/20090002）。
2. **相机 MakerNote 标签表 hook 生效**：v2.7.4 修复后日志为 `Bf.g rejected` → `target=Ig.g mapD=10 mapE=10 entries added=5 momentKeysPresent=5/5` → `camera_makernote=GLOBAL`。
3. 实测 12:44 之后 create 全部到达 CN 网关并被受理。

**仍失败的原因（服务端图片校验）**
- create 后任务轮询返回：`legend task error status=failed, errCode=21, errorMsg=该图片暂不支持`。
- 失败根因在**照片本身缺少一瞬元数据**，证据链：
  - 相机日志：`ExifInterface: getAttribute from XiaomiIdentifier: legendmode null`；
  - 新拍照片的 Xiaomi MakerNote 只有 6 个 HdrDisplay 标签（id 1,2,3,4,5,0x10），**没有 Moment 标签 0x30~0x34**（即使 MakerNote 标签表已扩展）；
  - 照片 XMP 中无 `meta/protobuf`（mediaeditor 日志：`JpegDataCleaner - injectBrightnessToXmp: meta/protobuf not found in XMP`），也无 legend protobuf；
  - mediaeditor `parseMakerNote` 对 Global 相机新照片始终 `version=null, momentType=null, ...`。
- 结论：**Global 相机 6.6 的拍摄链路不产生一瞬（legend/M9）元数据**（HAL 侧 `legendmode` 就是 null），相机侧 hook 只能保留标签、无法生成数据；服务端因此判定"该图片暂不支持"。
- CN 相机 CameraOnly（CN 6.7 替换 APK）在 Global ROM 上**无法启动/导致系统起不来**（已实测并回滚），因此不能靠换相机 APK 解决。

**可用配置（真机验证，能过鉴权并提交 create）**
- `simulate_cn_region = true`（默认开）
- `force_cn_cloud_endpoint = false`（默认关；硬编码 404 已废弃）
- `enable_cookie = true` + `cookie_mode = manual`（Cookie 需含 `hyperos_sec_common` 安全 token，可通过网页登录抓取）
- `force_legend_fallback = false`
- `force_legend_cloud_params`：开/关均可（服务端都受理 create；图片缺元数据仍失败）
- `sync_cn_camera_makernote = true`（保留 HAL 可能写入的 Moment 标签，有备无患）
- `trace_capture_params = true`（诊断用，可关）

**要真正出图，只有两条路**
1. **刷完整 CN ROM**（`ro.miui.build.region=cn` 等环境 + CN 系统组件），让相机/媒体编辑器原生产生并识别一瞬数据；
2. 等小米国际版后台/相机支持该能力（国际版曾缺 `ai_billing_record` 表，现已换 CN 网关但仍校验图片）。

### 历史结论（真机实测，v2.7.0）


1. **客户端全链路已打通**：门禁解锁、Cookie 注入、上传、create 请求（含参数）、MakerNote 回落、响应捕获均正常。
2. **国际版端点服务端故障**：`sgp-ai-photo.engine.intl.miui.com` 的 create 返回 `code=20090002`，响应 body 明确为
   `MySQLSyntaxErrorException: Table 'ai_platform.ai_billing_record' doesn't exist`（计费表缺失）→ 国际版后台未部署，客户端无法修复。
3. **CN 端点重定向不可用**：v2.7.0 把基址改为 `https://api.open.ai.xiaomi.com` 后，`/aiPhotoCloud/api/file/upload` 与 `/aiPhotoCloud/api/legendV2/list` 均返回 **404**。说明 CN 版真正的 AI 照片云基址不是 `api.open.ai.xiaomi.com`（该 host 只承载 auth 等接口），而是由 **CN 区域云配置动态下发**的另一个域名；在 Global ROM（`ro.miui.build.region=global`）下配置服务不会下发 CN 域名，海外网络也无法解析/访问。
4. 因此：**在 Global ROM 上，「二次增强」无法完成服务端 create**。若要真正使用，需要 **CN ROM/CN 区域环境**（配置会下发 CN 云基址，且国内网络可访问），或从一台真实 CN 徕卡设备获取其云配置下发的实际域名后硬编码（仍受区域风控限制）。


> ✅ **本版本完全不伪装系统属性**：不做 `resetprop`、不 hook `android.os.SystemProperties`、不修改任何 `ro.*` 值。改为**直接 hook App 内部的门禁判定方法**返回 `true`（只影响被 hook 的两个进程）。仍为静态分析产物，**未真机运行验证**。

> **v2.7.0 新增（云端点重定向到 CN）**：v2.6.2 抓到的服务端响应 body 证明 **国际版端点本身是坏的**：
> `code=20090002` 后跟 `### Error querying database. Cause: ... MySQLSyntaxErrorException: Table 'ai_platform.ai_billing_record' doesn't exist`——`sgp-ai-photo.engine.intl.miui.com` 没有计费表，`legendV2/create` 无法成功（客户端全链路已通）。
> v2.7.0 在 mediaeditor 进程 hook 云基址提供者（GLOBAL `jq.d.a` / CN `nm.c.a`），把 `intl.miui.com` 基址改写为 **`https://api.open.ai.xiaomi.com`**（CN 端点），使 upload/create 走 CN 后台；开关 `force_cn_cloud_endpoint`（**默认开**），诊断新增 `cloud_endpoint=`，可通过关闭开关做 A/B 对比。报文捕获仍记录真实响应。

> **v2.6.2 更新（logcat 可见的云端报文捕获）**：真机上「云端报文捕获/云端轨迹」一直"暂无"（Provider 写入被静默拒绝或 hook 未触发）。v2.6.2 把所有捕获点改为**双写**：`XposedBridge.log` + `android.util.Log.i("LeicaMoment", ...)`，并对 Provider 调用 try/catch 记录失败；新增直接 hook：
> ① `AiAvatarV2ResponseEntity` 构造器 → `resp_entity code/msg/trace`；
> ② Retrofit `HttpException`（`l21.j`）构造器 → `http_error code/msg`。
> 于是即使 in-app UI 仍显示"暂无"，也可用 `su -c "logcat -d | grep -E 'LeicaMoment|LSPosed-Bridge' | tail -60"` 直接看到 `create_params final`、`legend_create_body`、`cloud_resp_body`、`resp_entity`、`http_error` 与 `cloud_capture stored ok=...`。

> **v2.6.1 新增（云端报文捕获）**：真机 create 仍返回 HTTP 400 / `errorCode 20090002`（mediaeditor 内映射为 `INVALID_INPUT_PARAM_ERROR`，消息为空）。v2.6.1 在 mediaeditor 进程新增两组捕获：
> ① hook `CreateLegendV2TaskParamsJsonAdapter.toJson*`，把实际序列化的 create 请求 JSON 记录为 `legend_create_body=`；
> ② hook 混淆 OkHttp ResponseBody（`b11.f0`）的 `String` getter（`h()`），把服务端响应 JSON 记录为 `cloud_resp_body=`（≤4000 且像 JSON 错误体时，脱敏/截断 600 字符）。
> 两者经 Provider `append_cloud_capture` 本地保存最近 8 条，模块 ③ 卡片新增「云端报文捕获（最近8条）」+「刷新云端报文」，并并入「复制诊断信息」。用于确定 20090002 到底是哪个参数不合法。

> **v2.6.0 新增（hook 侧同步 CN/Global 相机 MakerNote 差异）**：
> 真机确认 Global 相机 6.6 的 Xiaomi MakerNote 标签表（`Ig.g`）只注册了 5 个 HdrDisplay 标签；CN 相机 6.7（`Bf.g`）还注册了 5 个 Moment 标签（`MomentVersion 0x30 / MomentType 0x31 / MomentSubMode 0x32 / MomentPro 0x33 / MomentBokehDepthVersion 0x34`，type=4）。Global 相机在保存时会重写 MakerNote，不认识的标签会被丢弃 → 拍出的照片没有 Moment 元数据。
> v2.6.0 在相机进程 hook：找到 MakerNote 标签表类（CN `Bf.g` / GLOBAL `Ig.g`），向静态 HashMap `d`/`e` 注入这 5 个 Moment 标签描述（并尝试追加到静态数组 `c`），使 Global 相机在保存时**保留** HAL 写入的 Moment 标签；开关 `sync_cn_camera_makernote`（默认开），诊断新增 `camera_makernote=CN|GLOBAL|none`。**不替换相机 APK、不改系统属性。**

> **v2.5.3 更新（真机首次打通云端！）**：v2.5.2 在真机（Global ROM + 强制重启 mediaeditor）上**解析成功并真正发起云请求**：
> 真实云基址 `https://sgp-ai-photo.engine.intl.miui.com`（非 CN 的 api.open.ai.xiaomi.com）；`uploadFile` **成功**（说明 Cookie/鉴权/上传全通）；
> 但 `CreateLegendV2TaskParams` 中 `aperture/focusX/focusY=null、oaid 空` → `create` 返回 **HTTP 400 / errorCode 439**。
> v2.5.3 新增：① hook `CreateLegendV2TaskParams<init>` 自动补全空参数（aperture=1.67f、focusX=2048、focusY=1536、oaid=稳定伪 UUID），开关 `force_legend_cloud_params`（默认开）；② 云端轨迹对 `aiPhotoCloud/legendV2` 请求追加**非消费式响应 body 片段**（`peekBody(1024)`+脱敏截断），用于读取服务端 400 的具体原因。

> **v2.5.2 修复（GLOBAL mediaeditor 兼容）**：v2.5.1 的回落 hook 只匹配 CN mediaeditor 混淆名（`lv.j0`），而用户机器是 **Global ROM + MIMediaEditorGlobal**（混淆名不同：`hz.q0`/`hz.k0`/`hz.p0`/`mg.g`），hook 静默跳过 → 「还是不行」。v2.5.2 增加通用安装器：先尝试 CN（`lv.j0→lv.d0, lv.i0`），再尝试 GLOBAL（`hz.q0→hz.k0, hz.p0`），并按形状 hook 静态方法返回构造的 `V2/M3/TRUE` 版本对象；另加第二道保险：hook MakerNote 映射 getter（CN `pc.g.a(String)` / GLOBAL `mg.g.a(String)`），对缺失的 `MomentVersion/MomentType/MomentSubMode/MomentPro/...` 合成值（2/9/2/1/4），确保 resolver 仍返回 V2/M3/TRUE。诊断新增 `legend_fallback_target=CN|GLOBAL|none` 显示实际命中的版本。

> **v2.5.1 新增（legend 版本回落注入，选项 B）**：真机日志确认照片缺 Xiaomi 一瞬 MakerNote（`LegendaryVersionResolver: version/momentType/subMode/isPro/depthMapVersion=null`），decodeImage 走 `legendInfo is null` 分支 → 「图片解析失败」，云请求不会发出。v2.5.1 在 mediaeditor 进程 hook `lv.j0.a(Lpc/b;)Llv/d0;` 与 `lv.j0.b(String,Lpc/b;)Llv/d0;`，返回构造的 `lv.d0(V2, M3, TRUE, M3/M10R)`，使 decodeImage 的兜底条件成立（日志 `legendInfo missing, allow fallback for V2 M3 bokeh`），照片可继续走到 cleaned upload/云端请求。默认开启，可在 ④ 功能开关关闭（`force_legend_fallback`）；全部反射解析 try/catch 失败跳过，不影响其他功能。风险：可能产生一次云请求/消耗额度，服务端仍可能拒绝。

> **v2.5.0 新增（强制启动二次增强）**：真机上相册的「二次增强」入口不显示，而已确认 mediaeditor 的 `LegendaryActivity` 为 `exported=true` 且从 `Intent.data` 取图片 Uri、自动进入二次增强流程。模块 App 新增「②.5 强制启动二次增强（排障）」卡片：
> ① 「选择照片并启动」（`ACTION_OPEN_DOCUMENT` 选图 → 显式 Intent 启动 `com.miui.mediaeditor/com.miui.mediaeditor.legendary.LegendaryActivity` + `FLAG_GRANT_READ_URI_PERMISSION`，失败自动回退组件名变体）；
> ② 「用相册最新照片启动」（运行时申请 `READ_MEDIA_IMAGES`/旧版存储权限 → MediaStore 查最新图片 → 同样的显式启动）；
> ③ 「复制 adb 启动命令」（`am start -n ... -d <uri> --grant-read-uri-permission`，作为 ROM 限制 exported 启动时的兜底）。配合 v2.4.3 的「云端请求轨迹」即可直接看到 `legendV2/create` 的真实 HTTP 结果。

> **v2.4.3 新增（设备端云端请求轨迹）**：沙箱出口为新加坡机房 IP，被小米风控拦截（同一 Cookie 在用户设备 micloud 200，在沙箱 STS/micloud 均 401），无法在开发环境"测到通过"。因此在 mediaeditor 的云请求拦截器（CN `nm.f` / GLOBAL `jq.g`）上增加 after-hook：记录 App **实际请求**的 `method + host + encodedPath`（不含 query/签名）、HTTP 状态码、是否带 Cookie（不记录任何值/体），环形缓冲最近 12 条，写日志并暴露到模块 Provider（`set_cloud_trace`/`get_cloud_trace`）；模块 UI 新增「云端请求轨迹（最近12条）」+「刷新云端轨迹」，并并入「复制诊断信息」。用于定位真实 AI 云基址与鉴权结果。

> **v2.4.2 更新（体检判定修正：micloud 200 = 鉴权通过）**：
> 依据真机诊断：网页自检 200；micloud `type=1/2` 在 Web UA 下返回 **HTTP 200**（v2.4.1 的合并优先级修复已解决此前 401）。
> ① 任何 **2xx 且无登录标识** 的 micloud 响应一律判为绿色「未被要求重新登录（鉴权通过，HTTP 200）」；响应体是业务/缺参错误时仅附加「业务参数不足，不影响鉴权结论」，不再降级为 WARN；
> ② AI 云平台探测行降级为**参考**（`参考：AI 云平台（基址由云端配置动态下发，可能 404）`）：已核实 CN mediaeditor 的 AI 基址由远端 CloudConfig（`AIConfig.url_config`/`UrlConfig`）下发，写死 `api.open.ai.xiaomi.com/aiPhotoCloud/...` 不可靠；404/405 判为「路径/基址不匹配（不代表 Cookie 无效）」，且**不参与**鉴权结论聚合；
> ③ 聚合结论：两条 micloud 均 2xx → 「Cookie 已被小米云鉴权接受 → 鉴权通过；请直接实测相册编辑二次增强，能否出图取决于服务端权益/设备白名单/硬件认证 token」；任一 micloud 401/403 → 红色「网页 Cookie 被拒」；纯网络问题 → 无法判定；
> ④ 诊断/帮助文本同步说明以上规则。

> **v2.4.1 更新（真实目标 API 探测 + Cookie 合并优先级修复）**：
> ① 新增第三项只读探测「AI 云平台（真实目标 API）」：**POST** `api.open.ai.xiaomi.com/aiPhotoCloud/api/legendV2/list`（body `{}`、8s 超时、不跟随重定向、只读不扣费），并支持 Web/桌面 Chrome/MiuiGallery 三种 UA 自适应重试——比 `i.mi.com` micloud 探测更贴近「二次增强」实际鉴权；
> ② **修复 Cookie 合并优先级**：按 `i.mi.com > api.open.ai.xiaomi.com > gallery.i.mi.com > account.xiaomi.com > xiaomi.com` 去重，避免 `account.xiaomi.com` 的同名 `serviceToken` 覆盖云端 token（这很可能是此前网页 Cookie 401 的原因之一）；并在组成检查/诊断中显示每个键的来源域名（不显示值）；
> ③ 「复制诊断信息」按钮（版本/各端点 HTTP 码/UA 类型/自检结果/Cookie 键名与长度/来源域名，绝不含令牌值）；
> ④ 结论措辞：micloud 401 但 AI 云平台未拒绝 → 「实际二次增强可能仍可用（以实测为准）」；两者均 401 → 「网页 Cookie 不被小米云鉴权接受（服务端行为）」，且**不再提示**使用系统账号 Cookie。

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

- 可复现构建：两次构建 sha256 一致（`bf096cf0…6f9c`，74,340 B，vc=24/vn=2.7.3）。
- 签名：apksigner `Verifies`，v1+v2+v3（`--min-sdk-version 21` 校验）。
- APK：`classes.dex`（146,396 B）、`assets/xposed_init` → `HookEntry`、`META-INF/xposed/{module.prop,scope.list(含 com.android.camera)}`；`aapt2 dump badging` vc=24/vn=2.7.3、minSdk 29/targetSdk 35、INTERNET + READ_MEDIA_IMAGES(+旧版存储 maxSdk32)；Manifest 含 `LoginActivity`(exported=false)、`<queries>` mediaeditor；dex 内 **0** 个 `de.robv` 类定义（仅类型引用，LSPosed 要求）。
- v2.7.3 新增字符串：`http_trace`、`native cookie`、`www-authenticate`。
- v2.7.2 新增字符串：`sysprop region hook`、`region prop`、`region prop rewritten`、`ro.miui.build.region`。
- v2.7.1 新增字符串：`simulate_cn_region`、`cn region simulated`、`cn_region`、`r21.c`、`gz0.b`。
- v2.7.0 新增字符串：`force_cn_cloud_endpoint`、`cloud endpoint rewritten`、`cloud_endpoint`、`jq.d`、`nm.c`、`api.open.ai.xiaomi.com`。
- v2.6.2 新增字符串：`LeicaMoment`、`create_params final`、`legend_create_body`、`cloud_resp_body`、`resp_entity`、`http_error`、`AiAvatarV2ResponseEntity`。
- v2.6.1 新增字符串：`append_cloud_capture`、`legend_create_body`、`cloud_resp_body`、`CreateLegendV2TaskParamsJsonAdapter`、`b11.f0`、`云端报文捕获`。
- v2.6.0 新增字符串：`sync_cn_camera_makernote`、`MomentBokehDepthVersion`、`camera_makernote`、`camera makernote map extended`、`Ig.g`、`Bf.g`。
- v2.5.3 新增字符串：`force_legend_cloud_params`、`CreateLegendV2TaskParams`、`legend cloud params filled`、`peekBody`。
- v2.5.2 新增 GLOBAL 映射与保险网字符串：`hz.q0`、`hz.k0`、`hz.p0`、`mg.g`、`pc.g`、`MomentSubMode`、`legend fallback hook installed`、`legend_fallback_target`。
- v2.5.1 新增注入相关字符串：`force_legend_fallback`、`legend fallback injected`、`legend fallback hook installed`、`lv.j0`、`Llv/d0`、`cleaned upload`。
- dex 中已无任何系统账号 Cookie 路径字样（系统账号捕获/填入捕获/cookie_mode/captured_cookie/hyperos_sec_common/登录系统小米账号/set_captured_cookie 均为 0）。
- v2.2.1 新增：WebView 错误处理方法（`onReceivedHttpError`/`onReceivedError`）、`i.mi.com/sts` 一次性自动恢复逻辑、直接打开 `https://i.mi.com/` 的登录/STS 入口；dex 中**已无**旧的无效应答 URL（`pass/serviceLogin?sid=i.mi.com` 与 `sts%3Fsign%3Dx` 均为 0）。
- 新增功能字符串已验证入 dex（检测 Cookie、serviceToken、3 个探测端点、Cookie 组成检查、2.2.0）；**未硬编码任何用户凭据**（源码与 dex 中均无粘贴过的 userId/cUserId/deviceId 字面量）；Cookie 值不写入日志/诊断文件。
- dex 中**不存在**任何属性伪装字符串：`ro.theme_customize`、`ro.miui.build.region`、`ro.theme_leica`、`ro.special_edition`、`SPOOF_PROPS`、`hookSystemProperties`、`resetprop`。
- Hook 目标均来自两国 smali 逐行证据：`docs/lsposed_hook_targets.md`（editor）、`docs/camera_hook_targets.md`（camera）。

---

## 6. 文件索引

| 路径 | 说明 |
|---|---|
| `out/leica_moment_port/lsposed/LeicaMoment_LSPosed_v2.7.3.apk`（源：`work/leica/lsposed/dist/`） | 最终 LSPosed APK（74,340 B，sha256 `bf096cf0…6f9c`） |
| `work/leica/lsposed/app/` | 模块源码（Java + manifest + xposed 元数据） |
| `work/leica/lsposed/app/README_APP.md` | 用户指南（中文，含两条使用路径/排错） |
| `work/leica/lsposed/build.sh` / `README_BUILD.md` | 无 Gradle 可复现构建 |
| `work/leica/docs/lsposed_hook_targets.md` | 编辑器 hook 逆向报告（smali 证据） |
| `work/leica/docs/camera_hook_targets.md` | 相机 hook 逆向报告（11 个 CN camera dex 证据） |
| `work/leica/lsposed/analysis/{hook_targets,camera_hook_targets}.json` | 机器可读 hook 目标 |
| `work/leica/rust_check/` | Rust 组件证据 |
