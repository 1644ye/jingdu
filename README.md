# 静读

1.3.0：新增底部自定义书架和左右滑动切换，沿用旧分组归属；首页名称、书架名称与顺序可调整。单书或批量选择是否在首页显示，列表／网格切换移到“更多”，继续阅读旁增加“立即同步”。“更多 → 软件更新”支持 GitHub Releases 自动检查、可选自动下载及系统确认安装。操作、发布约定与手机验收见 `docs/1.3.0书架与更新.md`。

1.0.2：更多 → 书架管理 → 批量操作支持备份所选书籍（ZIP）及批量添加标签；书籍详情的“标签”可添加标签，“编辑信息”可修改或删除标签。更多 → 备份与恢复支持全库 ZIP 压缩备份与 JSON/ZIP 恢复。所选备份包含对应正文、阅读记录、分组和设置；恢复仍为整体替换，不是合并导入。

适合诗集和小说的 Android 阅读器。正式内容格式为 `ebook-library` JSON v1；本地导入和阅读可离线使用。1.1.0 新增可选的 GitHub 只读书库及阅读进度同步，需要网络；私有仓库和同步需要分别登录授权。

## 1.1.0 GitHub 功能

1.2.0 补齐持久下载任务、暂停/恢复与后台执行、Range续传（服务器不支持时重下）、来源与个人标签独立管理、源移除标记和页面重建后的导航恢复。统一测试及手机操作步骤见 `docs/1.2.0测试清单.md`。这些功能不改变书源只读边界。

入口为“更多 → GitHub 书库与进度同步”。默认书源 `your-account/reading-library`，默认私有进度仓库 `your-account/reading-state`。支持手动更换登录账号、仓库所有者、仓库、分支、根目录与索引路径；书源和进度授权分开，App 不写入书源。首次登录及索引生成步骤见 `docs/GitHub登录配置.md`。

目录索引支持批量选择下载、检查正文更新及缓存浏览。每本远程书籍保留来源映射，仓库切换不会覆盖同 ID 的其他来源书籍。阅读进度使用内容 ID 与文字定位同步，离线并发修改需要选择位置；个人标签、笔记和配色目前不跨设备同步。背景任务受 Android 调度限制，可手动立即同步。

1.1.0 的实际验证范围记录在 `docs/1.1.0验收记录.md`。下文旧版本测试与安装说明保留历史记录，不能作为新登录和跨设备同步已验收的证明。

## 安装与开发

- Android 8.0 / API 26 或更新版本，支持手机和平板。
- 正式 Release 下载：[静读 v1.3.0](https://github.com/1644ye/jingdu/releases/tag/v1.3.0)。APK 是非调试 Release 构建，沿用此前开发签名，便于保留数据覆盖安装；尚未切换为应用商店发行签名。
- 交付安装包：`artifacts/QuietReader-1.3.0.apk`。把 APK 传到 Android 设备，使用系统安装器安装，打开“静读”即可。同一开发签名可覆盖升级此前 debug 版本，保留书库。通过 App 安装后续更新时需允许“安装未知应用”，并在系统安装器确认。
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

1. 首页点“更多 → 下载模板”，下载/复制诗集或小说模板；按 `docs/JSON协议.md` 填写并保存为 UTF-8。
2. 点“更多 → 导入 JSON”选择一个或多个文件。先校验并预览，阅读错误或未知字段警告，再选择导入。
3. 相同书籍 ID 默认跳过，可以改为更新或副本。同名不同 ID 是独立书籍。
4. 书架支持网格/列表、书名/作者/标签搜索、筛选排序。“更多 → 自定义书架”管理名称和顺序，底部点选或在书籍区域左右滑动切换。“更多 → 列表显示／网格显示”切换布局。点书籍进入详情或继续阅读；详情与管理页批量操作都能控制首页显示。“更多 → 书架管理”提供全选当前、取消选择及批量操作，也可长按书籍进入管理。全选只作用于当前筛选结果，隐藏书籍仍在管理页；返回书架会退出选择。
5. 阅读时可切换连续滚动/横向分页，点击空白切换工具栏。长按正文使用系统文字选择，再复制、划线或添加笔记。
6. 诗集的“更多”菜单可复制整首/选中诗行、收藏诗作、切换纯原文；设置中分别控制原文、拼音、译文、注释、全诗赏析。
7. 内容导出只包含书籍正文和元数据。完整备份还包含进度、笔记、分组、收藏及设置。恢复是整体替换，先校验再确认，恢复前自动保存保护备份，可在“更多 → 备份与恢复”导出。
8. “更多 → 应用配色”提供系统/浅色/护眼/深色主题及预设配色，可用 `#RRGGBB` 或 RGB 滑杆自由调整背景、卡片和按钮颜色。即时预览后点“保存配色”，下次启动自动恢复；文字按各自背景自动选择黑色或白色。配色用于书架和使用全局设置的阅读页，已单独设置主题的书籍可在阅读设置中恢复全局设置。自定义颜色包含在完整备份中。

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

2026-10-27 的1.0.1实际结果：31项核心测试、36项API 36模拟器仪器测试、18项独立Schema检查通过；Lint为0错误/14警告。真实截图、本次更新验收和1.0.0的300万字实测耗时见 `ACCEPTANCE.md`；并非全部原始18项用户操作组合均已完整验证。

- `:core:test`：协议结构、语义、错误路径、未知字段、复制ID及定位保护。
- `LibraryStoreTest`：真实Android SQLite事务、取消、更新、备份、迁移与回滚。
- `ReaderPaginationTest`：真实Android StaticLayout字符守恒、尺寸变化、诗行及选区定位。
- `MainActivityFlowTest`、`ImportFlowTest`、`ReaderActivityFlowTest`、`ReaderScrollFlowTest`：主要页面和阅读/导入集成。
- `PerformanceTest` 与阅读性能测试：记录设备、debug构建、1000诗/1000章300万字数据与阶段耗时。数据层报告写入应用私有 `files/performance-result.json`，日志标签 `QuietReaderPerf`。不要将桌面解析时间当作Android体验测量。
- `python -m pip install jsonschema==4.26.0` 后可运行 `python tools/verify_schema.py`，独立核对交付Schema和输入文件。
- `tools/android.ps1 -Action Test` 会保存实际测试输出并在未完全通过时返回失败。`FreshStart` 会清空模拟器中的本应用数据，仅供专用测试环境使用。

## 交付文件

GitHub Releases 提供 Release APK 和源码 ZIP，本地副本位于 `artifacts/`，APK和ZIP不纳入Git版本管理。源码包包含Gradle Wrapper、业务与测试代码、协议资源、测试所需书籍、说明和验收证据，不包含本机SDK路径、缓存或签名私钥。使用 `tools/build.ps1 -Tasks ':app:assembleRelease'` 构建非调试版本；更换构建机器时应安全迁移原签名，避免覆盖升级失败。

## 依赖

运行依赖 Kotlin 标准库与 Gson 2.11.0，均为 Apache-2.0 许可证；Android 平台接口不随源码复制。JUnit 4.13.2 用于测试。依赖许可说明见 `docs/依赖说明.md`。
