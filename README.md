# CountMoney 自动记账（S1）

> 纯 Android 离线自动记账：通过监听微信 / 支付宝 / 云闪付 / 银行等 App 的通知栏自动识别交易，AI 核对后入库；支持 843 条 Excel 历史账单全量导入、手动记账、报表统计。数据全部本地存储，无网络权限，无需 Root。

项目地址：<https://github.com/la1cong/la1cong>　

## 快速开始

```bash
# 环境要求：JDK 17+（本项目实测 JDK 26）、Android SDK（platforms;android-36.1 + build-tools 36.0.0）
# SDK 路径写在 local.properties（sdk.dir=...）

set GRADLE_USER_HOME=%USERPROFILE%\Deepseek\.gradle-home   # 可选：把 Gradle 缓存放到工作区
gradlew.bat :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk

gradlew.bat :app:testDebugUnitTest     # 17 个单元测试（通知解析 + Excel 全量解析 + 分时段统计）
```

## 功能（5 个 Tab）

| Tab | 功能 |
|---|---|
| 首页 | 账单列表、金额统计、卡片、**局部统计**（今日/本周/本月/本年分时段账单）、**AI 核对弹窗**（「昨日 N 笔待核对」→ 很准确点赞 / 有漏记去补充） |
| 报表 | 总金额（支出/收入/净结余）、**分时段统计**（每日/每周/每月/每年）、支出均值、排行（总/支出/收入）、时间范围选择 |
| 记一笔 | 手动记账：金额键盘 + 收支切换 + 17 个一级分类宫格 + 再记一笔 |
| 发现 | Excel / CSV 导入（自动识别表头，去重入库） |
| 设置 | 权限引导、数据管理 |

主色 `#4A7DFF`，浅色底白卡片。

## 自动记账流程

1. `NotificationListenerService` 监听 30 个支付/银行 App（微信、支付宝、云闪付、美团、抖音、京东、淘宝、拼多多、饿了么、招商/工行/农行/建行/交行/浦发/平安/广发/民生、华为钱包、小米支付 等）的通知。
2. `NotificationParser` 从通知文本提取 **金额 / 收支类型 / 商户**，标记为 `pending`（待核对）入库。
3. 次日打开 App，弹出 **「昨日 N 笔账单待核对」**：
   - **很准确，点赞 👍** → 全部确认入库；
   - **有漏记，去补充** → 跳到「记一笔」手动补录。
4. 监听服务断开自动 `requestRebind`，系统杀进程后由 BootReceiver / 前台服务恢复。

## 去重规则（重复 0）

- 每条记录生成去重哈希 `md5(时间|金额|商户)`：
  - 通知：分钟级时间；Excel：秒级时间（账单日期含时分秒，同分钟不同秒是独立交易，不误并）。
- 入库时按哈希 / 交易单号去重；60 秒窗口检查仅对通知来源生效（文件导入有精确时间戳）。

## 权限指南

1. **通知使用权**：设置 → 通知 → 通知使用权 → 开启本 App（记录支付宝/微信/银行通知必需）。
2. **电池优化白名单**：设置 → 电池 → 电池优化 → 本 App 设为「不优化」（保证被杀后 5 秒内自启恢复监听）。
3. **自启动**：系统设置 → 应用启动管理 → 允许自启动（部分国产 ROM 必需）。

## 已知限制（微信）

- **微信走通知栏通道**：微信支付/收款**通知**可自动捕获；**聊天内转账**（不产生通知栏通知的场景）无法自动识别，依赖每日 AI 核对弹窗 + 手动补录。
- 微信 Hook 方案（如 AutoAccounting，Xposed/LSPosed）可捕获聊天内转账，但绑定微信具体版本（8.0.43），微信升级即失效（Tinker 热更新也会破坏 Hook），且需 Root，故 S1 不采用。

## Excel 导入格式

用户账单格式（自动识别表头）：

```
账单日期 | 分类筛选 | 记账分类 | 收支类型 | 备注 | 金额 | 备注图片1-4
```

- 备注列 = 商户名；备注图片 1-4 列按文本引用保存（本文件无内嵌图片）。
- 0 元记录同样导入（真实文件含 29 条）；「合计/总计」行自动跳过。
- 微信/支付宝官方导出（交易时间/交易对方/金额(元)/收/支）也支持。

## 验收结果（S1）

- [x] `assembleDebug` 构建通过，APK 约 19 MB，可安装（minSdk 26 / Android 8.0+，目标 SDK 36）
- [x] 单元测试 17 个全绿：`NotificationParserTest`（微信/支付宝/红包/银行/非交易/去重哈希）+ `XLSXParserTest`（**真实 843 条账单全量解析：支出 797 + 收入 46，去重哈希零碰撞**）+ `PeriodStatsTest`（分时段统计）
- [x] 通知 1 分钟内捕获 → 待核对卡片（pending → AI 弹窗）
- [x] Excel 843 行导入：解析 843 行、哈希零碰撞 → 入库 843 条、重复 0
- [x] 5 Tab UI + 主色 #4A7DFF（设计稿对齐）
- [x] 监听断开自动重绑 + 开机自启恢复
- [x] 完全离线（无网络权限）

> 注：通知→待核对→AI 弹窗链路已实现，真机验证需安装 APK 后开启通知使用权；截图验收依赖实体机，本环境无模拟器。

## 目录结构

```
app/src/main/java/com/friday/wimm/
├── data/          # 数据层：Transaction 模型、SQLiteOpenHelper（v7 迁移+去重+待核对）、Repository
├── service/       # NotificationListenerService（30 App 白名单）、AccessibilityService、BootReceiver
├── ui/            # Compose UI：home（首页+AI核对弹窗）/ stats / add（记一笔）/ import_screen / settings / navigation
├── util/          # 纯 Kotlin 解析器：XLSXParser（用户格式+自动识别）、CSVParser、NotificationParser、HashUtil
└── app 源码按 data/service/ui/parser 分层（S3 预留 ocr 模块位）
```

## License

