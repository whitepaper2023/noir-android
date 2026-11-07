# NOIR Android 1.0.0 — 构建说明（自查记录）

## 工程
- 包名 com.lu.noir，versionCode 1，versionName 1.0.0，minSdk 26，targetSdk 34，compileSdk 34。
- AGP 8.8.0，Kotlin 2.1.0，Gradle 8.10.2，Java 17/21。
- 依赖只有 AndroidX core/appcompat/recyclerview/material、coroutines-android、Shizuku api+provider 13.1.5。无联网库，无 INTERNET 权限。

## 文件
- `app/src/main/java/app/noir/core/`：上游 NOIR 核心原样移植（Classifier/Domains/Exposure/Models/Rules/Advisor/Report + apk/ApkScan/Axml/Manifest/Trackers），保留 MIT 版权头。Tui（终端 UI）未移植，不需要。
- `com/lu/noir/Scanner.kt`：PackageManager 盘点 + dex 端点抽取（仅 INTERNET 应用）+ AXML 清单 + 轻量 DeepScan（dex 字符串检索，无 jadx）。
- `com/lu/noir/ShizukuShell.kt`：NoirShellService（AIDL Stub，destroy=16777114）+ bindUserService 绑定、runCommand（60 秒超时，返回 exit/stdout/stderr）。
- `com/lu/noir/ApplyEngine.kt`：安全处置只做 appops，执行前查原模式写 rollback.txt；卸载逐个确认，记录 pm install-existing。绝不碰 android / com.lu.noir / moe.shizuku.manager。
- `com/lu/noir/ReportExporter.kt`：report.json/md/html、domains-*.txt、plan.txt，写到应用专属外存 noir-out/。
- `com/lu/noir/MainActivity.kt`：单 Activity + RecyclerView 列表 + 详情对话框 + 搜索过滤 + 导出/处置确认。
- `.github/workflows/build-noir-release.yaml`：workflow_dispatch，CI 只出 unsigned 包，签名本地做。

## 自查（2026-11-06，本地无 Java/Android SDK，无法本地编译）
- 全部 .kt/.kts 大括号/圆括号/方括号成对，全部 .xml 解析通过。
- 逐文件重读：AIDL 方法与 Stub 实现一致（destroy/runCommand/whoami）；UserServiceArgs 链式调用与官方 demo 一致；ShizukuProvider 与 queries 已声明；R.id 与布局一致；Report/Classifier/Advisor/Domains API 签名与上游一致。
- 无 keystore、无密码、无节点数据进仓库。
- CI 是编译检查：若 CI 报错，按报错逐项修，不叠加未验证版本。

## 已知取舍
- DeepScan 是 dex 字符串检索，不是 jadx 完整反编译。
- 没装 Shizuku 时只读审计 + 导出，处置不可用。
- 读不到的 APK（个别厂商）只按权限打分，端点记未知，不中断扫描。
- 导出目前是写文件到应用专属目录，不走 SAF 分享，Lu 用文件管理器 / USB 取走。

## 1.0.1（2026-11-06，真机反馈）
- 卸载点完等满 60 秒报 `timeout after 60s`：`runCommand` 在 IO 线程绑 UserService，回调一直不回。
  改法：先 pingBinder + 查授权，不行立刻返回；绑定改主线程发起，只等 15 秒；绑上后命令仍给 60 秒。
- 详情弹窗的“卸载此应用”按钮被内容顶出屏幕底部、只剩半截：按钮改放进弹窗自己的布局
  （内容区 weight=1，按钮行固定在底），关闭/卸载始终可见。
- 卸载完加复核：`pm list packages --user 0 <pkg>` 查包还在不在，结果弹窗写明复核结论；
  确认已卸载后从列表里去掉该应用并更新统计，不用重新扫描。
- 图标：黑底白盾 + 放大镜（PIL 生成），自适应图标（mipmap-anydpi-v26 + xxxhdpi 前景）
  与 legacy/round 各密度 PNG，manifest 已接 `android:icon` / `android:roundIcon`。
- 版本：versionCode 2，versionName 1.0.1。签名仍用 Bash 那把 release.keystore（别名 clashfirewall，之前记的 CLASHFIR 是错的，keystore 里只有这一个条目）。

## 1.0.2（2026-11-06，真机反馈）
- 1.0.1 复测（23:00）：卸载仍失败，exit=-1，15 秒就报“绑定 Shizuku 服务超时”，不再等 60 秒；
  详情弹窗按钮已修好（结果弹窗完整显示）。结论：UserService 绑定在 Lu 的 vivo 上始终不回，
  预检 pingBinder/授权都通过、服务端活着，问题在绑定这一步本身，不是超时时间。
- 改法：runCommand 先走 Shizuku.newProcess 直跑命令。13.1.5 里这个方法是 private static
  （AAR 类文件核实：`newProcess([Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Lrikka/shizuku/ShizukuRemoteProcess;`，
  返回的 ShizukuRemoteProcess 是 public 类、继承 java.lang.Process），用反射调它；
  服务端直接 fork `sh -c <命令>` 执行，不用绑服务、不用另起应用进程，正好绕过卡住的一步。
  反射不可用（NoSuchMethodException/SecurityException）时回落到原来的 UserService 绑定（15 秒超时）；
  直跑中服务端抛错直接报 exit=-1，不再走绑定。两种路子命令本身都给 60 秒。
  计时等待用 ShizukuRemoteProcess 自带的 waitForTimeout（服务端计时），不用基类
  Process.waitFor(long, TimeUnit)——后者靠轮询 exitValue()、只认 IllegalThreadStateException，
  远程进程上不可靠（独立复核指出并修过）。
  注意：官方文档说 newProcess 已废弃、计划在 API 14 移除，但我们钉死 13.1.5、服务端 13.6.0 仍实现它；
  若以后升 API 14 须重新评估。
- 停用/启用（2026-11-06 23:10 真机反馈，Lu 要求加按钮）：vivo 受保护系统应用（如孩子守护组件）
  用 pm uninstall 会弹“安全验证”要求登录 vivo 账号，这是 vivo 系统包管理器的拦截，执行路径换不成。
  替代办法是停用：详情弹窗加“停用此应用（需 Shizuku）”按钮，执行 pm disable-user --user 0，
  停用后复核用 pm list packages -d --user 0 查已停用列表；已停用的显示“启用此应用”按钮，
  执行 pm enable --user 0，复核是包不再出现在已停用列表里。停用恢复命令（pm enable）写进
  rollback.txt。三个按钮竖排、全宽固定在弹窗底部；扫描时按 ApplicationInfo.enabled 记停用
  标记，列表行与详情都显示“已停用”，停用/启用后就地更新标记不用重扫。受保护包
  （android / com.lu.noir / moe.shizuku.manager）既不能卸载也不能停用。
- 版本：versionCode 3，versionName 1.0.2。签名仍用同一把 release.keystore（别名 clashfirewall）。

## 1.0.3 (2026-11-07, 真机反馈)
- 受保护提示（2026-11-07 00:05 真机反馈，Lu 要求加）：com.bbk.account（vivo账号）卸载报
  `Failure [DELETE_FAILED_USER_RESTRICTED]`、停用报 `Cannot disable ... no root permission`，
  报错原文看着像程序出 bug。已对照 AOSP 源码核实：这是 vivo 受保护包列表的硬拦
  （mProtectedPackages.isPackageStateProtected），Shizuku 的 shell 身份过不去，要 root 才行。
  改法：结果弹窗里把这两类系统拦截翻译成人话（“这是系统受保护应用，系统不让卸载/停用，
  需要 root 才能动，不是 Shizuku 的问题。”），只认实际碰到过的两种原文，不猜；
  碰到过拦截的包记进 prefs（protected_pkgs），下次扫描列表行与详情都标“受保护”。
  注意：vivo 账号验证弹窗那次（23:10，com.vivo.familycare.widget）pm 的返回未知，未纳入翻译。
- 版本：versionCode 4，versionName 1.0.3。签名仍用同一把 release.keystore（别名 clashfirewall）。

## 1.0.4 (2026-11-07, Lu 要求)
- 恢复按钮（Lu 2026-11-07 00:28 要求）：主界面加“恢复”按钮，读 rollback.txt，
  把里面的非注释行逐条经 Shizuku 执行，逐条报 exit 与输出；恢复命令都是幂等的
  （appops 设回原模式、pm install-existing、pm enable），恢复完文件留着，再点
  一次也无害；成功启用的应用就地去掉“已停用”标记，恢复后建议重扫一次刷新列表。
- rollback.txt 追加修复：applySafe 原来每次都覆盖 rollback.txt，跑一次安全处置
  就会把之前卸载/停用的恢复命令冲掉，改成追加；同一个 (pkg, op) 只保留最早那条
  回滚行（恢复的是最初状态），同一处置跑第二遍不再重复写——第二遍查到的“原模式”
  已是第一遍改完的状态，再写会把恢复目标写错（恢复完还停在 ignore）。
- 图标（Lu 选 7 号）：红色霓虹发光盾 + 蓝色霓虹锁，黑底。AI 生成图是黑底上的加色光，
  转换时按 alpha = 最大通道值去掉黑底做自适应前景（脚本 make_icon_from_image.py），
  legacy 图标在 #101014 底上合成；全套尺寸 mdpi~xxxhdpi + 自适应前景（xxxhdpi 432px）。
- 闪退修复（Lu 2026-11-07 00:44 反馈：开始扫描一段时间后闪退，之前版本也出现过）：
  旧代码扫 dex 是整文件 readBytes() 进内存，一个大 dex（微信这类几十 MB）一份字节
  数组，deepScan 还要再复制一份同样大小的 String，峰值上百 MB，堆一紧张就
  OutOfMemoryError；Error 不进 catch (Exception)，直接崩掉整轮扫描。改法：
  ApkScan.urls 与 Scanner.deepScan 都改成流式读（1MB / 64KB 一块），块之间留重叠
  （URL 320 字节、关键字 64 字节），跨块的 URL 与关键字也能扫到，内存峰值 1MB 出头；
  单个应用的 try/catch 与 Manifest 解析、扫描入口的 catch 都改抓 Throwable，
  单个应用出任何问题都只跳过它，不再崩整轮；另加崩溃日志——未捕获异常写进
  noir-out/crash.txt，扫描失败写进 noir-out/scan-error.txt，下次再出问题有据可查。
- 版本：versionCode 5，versionName 1.0.4。签名仍用同一把 release.keystore（别名 clashfirewall）。

## 1.0.5 (2026-11-07, Lu 要求）

Lu 说主界面 5 个按钮太丑、底色不要黑色。改版只动界面，不动扫描/处置逻辑：

- 主题：themes.xml 从 `Theme.MaterialComponents.DayNight.NoActionBar`（深色模式下全黑底、按钮近黑）改成 `Theme.MaterialComponents.Light.NoActionBar`，强制浅色，手机开深色模式也不变黑；colorPrimary 改红 `#D32F2F`（与新图标红盾同色），colorPrimaryDark `#9A0007`，colorAccent 蓝 `#1565C0`（与锁同色，进度条着色用）。
- 布局（activity_main.xml 全重写）：根布局浅灰底 `#F4F6F8`、16dp 边距；状态与列表各放进一个白底 MaterialCardView（16dp 圆角、2dp 阴影）；开始扫描是红色通栏大按钮（52dp 高、白字 16sp、12dp 圆角）；其余 4 个按钮白色圆角 2x2（48dp 高、深色字、#DDDDDD 1dp 描边），用框架 GridLayout（columnWeight 均分，minSdk 26 没问题）；搜索框 48dp 高、圆角白底（新文件 drawable/search_bg.xml，12dp 圆角、#DDDDDD 描边）；列表卡片占剩余高度。全部 view ID 不变，MainActivity 接线不用改。
- 列表项（item_app.xml 全重写）：12dp 内边距、ripple 背景、标题深色加粗 15sp、副文/分数灰色 13sp、底部 1dp #EEEEEE 分隔线。
- 分数着色（MainActivity.kt，onBindViewHolder）：风险分 ≥40 红 `#D32F2F`、≥25 橙 `#E65100`、其余绿 `#2E7D32`。
- 详情弹窗的按钮是代码里 Button(this) 现建的，浅色主题下自动变白底红字，不用改。
- 版本：versionCode 6，versionName 1.0.5。签名仍用同一把 release.keystore（别名 clashfirewall）。

## 1.0.6 (2026-11-07，修扫描中途退出）

根因（Lu 2026-11-07 06:43 反馈“又突然退出了”、06:51 补“扫描快结束时退出了”，并附 scan-error.txt）：扫描跑在 MainActivity 自己的 `CoroutineScope(SupervisorJob())` 里，`onDestroy()` 一调 `scope.cancel()`，扫描就被取消——切后台、锁屏后 vivo 回收 Activity、转屏，都会让一场扫了几分钟的扫描在快结束时白跑，界面直接没了。证据：scan-error.txt 里 2026-11-07 01:12:20 那条正是 `JobCancellationException: Job was cancelled; job=SupervisorJobImpl{Cancelling}`，而 crash.txt 是空的——不是内存爆了、不是程序崩了，是 Activity 被系统销毁、扫描被连带取消。1.0.4 的流式读修复仍在包里，没白做；这次是另一个问题。

修法（只动扫描的归属，不动扫描逻辑）：
- 新文件 ScanManager.kt（应用级单例）：扫描跑在它自己的 `CoroutineScope(SupervisorJob() + Dispatchers.Main)` 里，Activity 销毁、重建都不影响；进度与结果存在单例里（lastDone/lastTotal/lastPkg/lastResult/lastError），Activity 在 onCreate 里接回——扫一半重开应用能看到进度接着跑，扫完重开能看到结果；失败才写 scan-error.txt（日志逻辑从 MainActivity 搬过来），用户主动点停不算错误、不记日志。
- MainActivity：删 Activity 级的 scanJob/lastResult，startScan 改调 ScanManager.toggleScan（扫一半再点一次 = 主动停止，状态显示“扫描已停止。”）；onDestroy 只注销进度回调，不再取消扫描；Activity 级 scope 留给卸载/停用/处置/恢复那些短操作，照旧 onDestroy 取消。
- AndroidManifest：MainActivity 加 `configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize"`，转屏不再重建 Activity。
- Scanner.scan：每扫一个应用开头调一次 `ensureActive()`，用户点停能立刻停，不用等整轮跑完（阻塞式 dex 读本身不停顿，取消信号靠这里递进）。
- 版本：versionCode 7，versionName 1.0.6。签名仍用同一把 release.keystore（别名 clashfirewall）。

## 1.0.7 (2026-11-07，处置过的应用标“已处置”、分数扣减）

需求（Lu 2026-11-07 12:17 反馈）：处置后重扫，需要处置的还是同一批应用——看着像没起作用。要求处置过的有标识、分数也降。

根因：安全处置只把 PROJECT_MEDIA / GET_USAGE_STATS 设为 ignore，不改权限、端点、dex；扫描按这些打分、按这些给建议，所以重扫后同一批应用、同一条建议、同一个分数，一点没变。

修法：
- 记录：ApplyEngine.applySafe 成功执行一条（exit=0）就把 (pkg, op) 以 "pkg|OP" 记进 prefs（"noir" / "applied_ops"）；恢复时设回非 ignore 的清掉对应条。1.0.7 之前处置过、只留在 applied.txt 里的，第一次扫描时从文件里并进来（只认 `ok (0)` 的行，合并不是覆盖，恢复后清空过的也不会被重新种回来）。applied.txt 每次处置会被重写，prefs 才是 durable 的记录。
- 标识：Scanner 读 prefs，AppEntry 新增 appliedOps 字段；列表行追加“ · 已处置”（和“ · 受保护”同款），详情弹窗写“已处置：禁止录屏（-5分）、禁止读取使用统计（-1分）”，汇总行加“已处置 N 个应用”。
- 扣分：Rules.remediatedScore——只扣被禁掉的那部分，不虚构降幅。PROJECT_MEDIA 被禁扣 5（权限本身 1 + 录屏加权 4），GET_USAGE_STATS 被禁扣 1（该权限在总数里只占 1），下限 0。权限、端点、dex 都还在，所以大应用分数不会一下掉出高风险，标识才是主要信号。分数一律从 Rules.score 原始分重算再扣，禁止在已扣过的分数上叠加（扫描里和处置/恢复后就地刷新里都是同一口径）。
- 不再重复建议：Advisor.advise 跳过已处置的那条 autoSafe 建议（REVIEW、BLOCK_DNS 不受影响），重扫后“安全处置 N 条”会跟着降。
- 就地刷新：处置完、恢复完立刻改列表（标记、分数、建议、汇总），不用重扫；Activity 重建后从 ScanManager 取到的也是最新列表。
- 版本：versionCode 8，versionName 1.0.7。签名仍用同一把 release.keystore（别名 clashfirewall）。

## 1.0.8 (2026-11-08，同步上游 NOIR 0.4.2）

上游 0.4.2 是安全修正版，标题写明 0.4.1 之前都要升。逐文件 diff 了咱们移植的 11 个核心文件（本地 vs 上游 main HEAD），同步了其中有实质影响的改动。

### 最要紧的修正：PROJECT_MEDIA 不是“禁止录屏”
上游 commit 56246fda 删掉了录屏自动建议：PROJECT_MEDIA 这个 app-op 只控制常驻授权，设成 ignore 拦不住录屏。咱们 1.0.7 写的是“禁止录屏（-5分）”，说法是错的。1.0.8 改法：
- Advisor.advise 不再产生 PROJECT_MEDIA 自动建议（MEDIA_KEEP 白名单和 mediaWorthFlagging 整段删掉）；ActionType 标签按上游改名“撤销常驻录屏授权”。
- Rules.remediatedScore 删掉 -5 扣分（录屏能力还在，分数不该扣）；GET_USAGE_STATS 的 -1 保留（上游保留了这条建议，读使用统计是真被禁掉了）。
- 详情弹窗里 1.0.7 已处置过的旧记录还显示这一项，写“撤销常驻录屏授权”，不带 -5；那是实际发生的事（常驻授权已撤销），不是“录屏已禁”。
- ApplyEngine.commandFor/opFor 的 PROJECT_MEDIA 分支留着（旧的回滚/恢复和 1.0.7 迁移还要认它），不再有新建议会走到这里。

### 扫描准确性（同步上游 0.4.2）
- Rules：SENSITIVE 加粗定位、读电话号码、身体传感器、读外部存储、读音频；上报/指令路径词扩了（/stats、/logs、/tracking、/analytics、/metrics、/crash 等以前漏了）；isUpload/isCommand 只看 URL 路径，查询参数里的上报词不再算数（误报）。
- Domains：追踪标签优先于通用功能标签；"report"/"analytics" 从前缀匹配改成子串匹配（BLOCK_CONTAINS）；只有 stat / stats / statistic* 才算追踪器，static、station、status 不算（以前 startsWith("stat") 会误伤）。
- Trackers.fromEndpoints：按 host 匹配，不再拿整条 URL 子串去搜（token 出现在路径里也会误算）。
- Manifest：组件加 enabled（enabled=false 不算对外暴露）；应用级 permission 继承到组件；provider 的 read/writePermission 也算数；allowBackup 缺了默认 true（Android 默认值，不是未知）；cleartext 按 targetSdk<28 判；manifest 超 8MB 直接跳过。
- Axml：畸形 AXML 的边界检查（偏移量、字符串池起点、每条字符串入口、u16/u32 越界保护），越界返回空串/空表，不抛异常。
- Exposure：hostOf 去 userinfo、问号、# 号、IPv6 方括号；READ_PHONE_NUMBERS→设备标识、READ_MEDIA_AUDIO→文件/相册。
- Advisor BLOCK_DNS：按不同上报 host 数，不按端点数。
- Report：JSON 加 leaksByType 稳定键；Markdown 表格值转义（md()）；HTML 汇总按不同上报 host 数；风险徽章样式显式映射（以前 lowercase() 在个别 locale 下会丢样式）。
- ApkScan：单条 dex 读坏了只跳过这一条，前面 dex 已扫到的 URL 留着；1.0.4 的流式读法保留（上游还是整文件 readBytes，没采纳流式读）。
- ApplyEngine.applySafe：回滚行只在执行成功后才写进 rollback.txt（以前执行前就写，失败的命令也会留一条没执行过的回滚行）。

### 刻意没同步的
- i18n/Lang 英文界面：咱们只要中文，不接 Lang 参数。
- 上游 CLI 的东西：--apply/--yes/--purge 参数解析、jadx、端点缓存、工具下载校验、TUI：手机上没有。
- 分包 APK 与 classes16.dex+：咱们本来就处理（Scanner 读 sourceDir + splitSourceDirs；ApkScan 按 classes* 前缀扫全部 dex）。
- Manifest catch：上游从 Throwable 收窄到 Exception；咱们保持 Throwable（设备上畸形文件抛 Error 也不该拖垮扫描）。
- Rollback 不写设备序列号：上游 rollback 带序列号；咱们是单设备用，恢复只在本机跑，没加。
- 版本：versionCode 9，versionName 1.0.8。签名仍用同一把 release.keystore（别名 clashfirewall）。
