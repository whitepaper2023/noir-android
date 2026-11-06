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
