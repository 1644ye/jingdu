# 静读

适合诗集和小说的 Android 离线阅读器。正式内容格式为 `ebook-library` JSON v1；不需要账号和网络，不申请网络权限。

## 安装与开发

- Android 8.0 / API 26 或更新版本，支持手机和平板。
- 当前安装包为开发签名的 debug APK。它适合本地安装与验证，尚非应用商店发行包。
- 交付安装包：`artifacts/QuietReader-1.0.0-debug.apk`。把 APK 传到 Android 设备，使用系统安装器安装，打开“静读”即可；应用没有额外运行时权限要求。
- Android Studio 打开本目录。需要 Android SDK 36、JDK 17 或更新版本；本次构建使用 JDK 25、Gradle 9.3.1、AGP 9.1.0。
- 新机器在 `local.properties` 配置 `sdk.dir=你的SDK路径`，或设置 `ANDROID_HOME`。该文件不应提交版本管理。
- 设置 `JAVA_HOME` 后执行：

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows 也可使用 `tools/build.ps1 -JavaHome '你的JDK路径'`。该脚本默认执行上述三个任务，支持传入 `-Tasks`。首次构建需要下载 Gradle 和 Maven 依赖；应用运行本身离线。

输出：`app/build/outputs/apk/debug/app-debug.apk`，测试包位于 `app/build/outputs/apk/androidTest/debug/`。设备已连接时：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.quietreader.test/android.test.InstrumentationTestRunner
```

仪器测试会创建独立数据库或隔离测试书籍；UI导入/恢复测试会暂存并恢复书库。建议在专用模拟器运行测试。

## 使用

1. 首页点“下载模板”，下载/复制诗集或小说模板；按 `docs/JSON协议.md` 填写并保存为 UTF-8。
2. 点“导入 JSON”选择一个或多个文件。先校验并预览，阅读错误或未知字段警告，再选择导入。
3. 相同书籍 ID 默认跳过，可以改为更新或副本。同名不同 ID 是独立书籍。
4. 书架支持网格/列表、书名/作者/标签搜索、筛选排序、多分组、长按批量操作。点书籍进入详情或继续阅读。
5. 阅读时可切换连续滚动/横向分页，点击空白切换工具栏。长按正文使用系统文字选择，再复制、划线或添加笔记。
6. 诗集的“更多”菜单可复制整首/选中诗行、收藏诗作、切换纯原文；设置中分别控制原文、拼音、译文、注释、全诗赏析。
7. 内容导出只包含书籍正文和元数据。完整备份还包含进度、笔记、分组、收藏及设置。恢复是整体替换，先校验再确认，恢复前自动保存保护备份，可在“模板与备份”导出。

## 模板、示例与测试数据

- `core/src/main/resources/protocol/ebook-library-v1.schema.json`：机器可校验内容 Schema。
- 同目录 `poetry-template.json`、`novel-template.json`：最简可填写模板。
- 同目录 `poetry-example.json`、`novel-example.json`：用户提供的原样完整示例。
- 以上资源打包在应用中，不依赖开发机路径。首次示例有“示例”标签，删除后不会重新插入。
- `python tools/generate_test_data.py` 生成1000首诗、1000章/300万中文字符小说和10本批量文件；输出及计数清单在 `test-data/generated/`。

## 实现与限制

Kotlin 原生 View/TextView、StaticLayout 实际排版、SQLiteOpenHelper 事务、Gson 严格 JSON 解析。正文按篇章入库，阅读仅保留有限排版片段和章节缓存，不创建全书正文节点。定位使用稳定内容 ID 和 UTF-16 偏移；文本更新关联不可靠时，保留记录并标为“原位置已变化”。

完整实现范围见 `REQUIREMENTS.md`；当前状态见 `IMPLEMENTATION.md`；真实测试及未验证项见 `ACCEPTANCE.md`。新开发阶段没有复制候选开源阅读器的代码。

已文档化上限：内容文件32MiB；每批16文件、累计64MiB；每文件1000书；每书10000章节/诗作、20万内部节点；32层JSON；单章/单诗序列化1MiB；书籍元数据256KiB；单条笔记128KiB；外部完整备份128MiB。超限会明确报错，不写入当前内容文件。实际可处理大小还受设备可用内存限制，输入会按当前堆内存预算提前拒绝，完整备份仍使用内存JSON树。备份与迁移细节见 `docs/备份与数据.md`。

系统字体选择使用设备可用的 serif / sans-serif / monospace 字体族；中文具体字形取决于系统字体回退。拼音按行显示，不推测逐字对齐。

## 测试

2026-10-26 实际结果：31项核心测试、33项API 36模拟器仪器测试、14项独立Schema检查通过；Lint为0错误/11警告。真实截图和300万字实测耗时见 `ACCEPTANCE.md`；并非全部18项用户操作组合均已完整验证。

- `:core:test`：协议结构、语义、错误路径、未知字段、复制ID及定位保护。
- `LibraryStoreTest`：真实Android SQLite事务、取消、更新、备份、迁移与回滚。
- `ReaderPaginationTest`：真实Android StaticLayout字符守恒、尺寸变化、诗行及选区定位。
- `MainActivityFlowTest`、`ImportFlowTest`、`ReaderActivityFlowTest`、`ReaderScrollFlowTest`：主要页面和阅读/导入集成。
- `PerformanceTest` 与阅读性能测试：记录设备、debug构建、1000诗/1000章300万字数据与阶段耗时。数据层报告写入应用私有 `files/performance-result.json`，日志标签 `QuietReaderPerf`。不要将桌面解析时间当作Android体验测量。
- `python -m pip install jsonschema==4.26.0` 后可运行 `python tools/verify_schema.py`，独立核对交付Schema和输入文件。
- `tools/android.ps1 -Action Test` 会保存实际测试输出并在未完全通过时返回失败。`FreshStart` 会清空模拟器中的本应用数据，仅供专用测试环境使用。

## 交付文件

本地交付目录 `artifacts/` 内提供 debug APK、完整源码ZIP、模板与示例ZIP、SHA-256校验清单以及测试证据。APK和ZIP不纳入Git版本管理；从GitHub克隆后，按上文构建即可生成安装包。源码包包含Gradle Wrapper、所有业务与测试代码、协议资源、生成数据、说明和验收证据；不包含本机SDK路径、缓存和开发签名私钥。可用 `tools/package.ps1` 从已构建APK重新打包。

## 依赖

运行依赖 Kotlin 标准库与 Gson 2.11.0，均为 Apache-2.0 许可证；Android 平台接口不随源码复制。JUnit 4.13.2 用于测试。依赖许可说明见 `docs/依赖说明.md`。
