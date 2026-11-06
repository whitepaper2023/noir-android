# NOIR Android

把桌面工具 NOIR 做成 Android APK：本机审计应用权限、上报端点与数据流向，给出可回滚的处置。

上游 NOIR（Verlintas，MIT）：桌面 Kotlin CLI，经 adb 扫描已连设备。核心分类、打分、建议、端点抽取、AXML 解析直接复用上游代码（见 `app/src/main/java/app/noir/core/`，保留原 MIT 版权头，另见 `LICENSE-NOIR`）。

## 它做什么

- 盘点：列出全部应用、权限、共享 UID、系统/可调试、安装来源。
- 抽端点：直接读本机 APK 的 dex 扫 URL，不把包拉到电脑。
- 分类打分：按包名和域名认厂商与类别，结合权限和上报端点算风险分。
- 数据关联：权限翻译成能拿到什么数据，再和上报域名配对。
- 清单层：解析 AndroidManifest.xml 二进制 AXML，找无权限保护的导出组件、明文流量、备份、debuggable、targetSdk。
- 轻量 DeepScan：dex 字符串检索 Runtime.exec、DexClassLoader、MediaProjection 等信号（手机上跑不了 jadx 完整反编译，这是已知取舍）。
- 给建议：只把 appops 级动作标为可自动执行，卸载必须逐个确认。
- 导出：report.json / report.md / report.html、domains-trackers.txt / domains-other.txt / domains-functional.txt、plan.txt。

## 它不做什么

- 不联网、不上传、不需要账号。
- 不改系统分区，不需要 root。
- 没装 Shizuku 时只读审计 + 导出，不执行任何处置。
- 默认不自动卸载任何东西。

## Shizuku 设置（处置功能需要）

1. 安装 Shizuku App。
2. Android 11+：开发者选项 → 无线调试 → 在 Shizuku 里配对启动；重启后需重新启动 Shizuku。
3. 回到 NOIR，点“检查 Shizuku”，在 Shizuku 弹框里授权。
4. 处置前会先查原模式写 `rollback.txt`，成功执行的写 `applied.txt`，都在应用专属目录 `Android/data/com.lu.noir/files/noir-out/`。

安全处置只执行 `cmd appops set --user 0 <包> PROJECT_MEDIA|GET_USAGE_STATS ignore`；卸载是 `pm uninstall --user 0 <包>`，恢复是 `pm install-existing --user 0 <包>`。绝不碰 `android`、NOIR 自己、Shizuku。

## 构建

```
./gradlew :app:assembleRelease
```

CI 只出 unsigned 包，签名在本地用固定 keystore 做，密钥不上 GitHub。

## 许可

MIT，见 `LICENSE-NOIR`（上游）。

## 免责

只用于你自己的设备，或你获得明确授权的设备。报告是静态证据（“这个应用具备这个能力，并且代码里有这个端点”），不能证明某次具体传输发生过。
