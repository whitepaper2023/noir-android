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
