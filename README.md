# 静读 · Jingdu

适合诗集和小说的 Android 阅读器，使用统一 JSON 格式导入正文。可以只在本地离线阅读，也可以把自己的 GitHub 仓库作为只读书源，再用一个独立私有仓库同步阅读进度。

支持自定义书架、底部点选与横滑切换、首页显示选择、搜索与标签、连续滚动与分页、书签和笔记、应用配色，以及 JSON／ZIP 备份。

## 从哪里开始

| 想怎么用 | 需要准备 | 阅读顺序 |
| --- | --- | --- |
| 只在本机看书 | Android 8.0 及以上设备、书籍 JSON | [安装](#1-安装应用) → [本地导入](#2-只在本机阅读无需-github) |
| 从自己的 GitHub 书源下载 | GitHub 账号、书源仓库、书源应用授权 | [模板和示例](#3-直接下载模板和示例) → [创建仓库](#4-创建自己的-github-仓库) → [配置书源](#5-登录并连接书源) |
| 在多台设备之间同步进度 | 上述书源、另一个私有进度仓库、同步应用授权 | 完成书源下载，再按[同步步骤](#6-配置阅读进度双向同步)操作 |
| 自行构建、使用自己的登录应用 | 项目源码、Android 开发环境、两套 GitHub App | [自行部署](#11-自行构建与配置-github-登录应用) |

**1.3.1 起不预填个人书源或进度仓库。覆盖升级保留本机已有登录、仓库、书籍和阅读记录；新安装需要使用自己的 GitHub 账号授权。**

## 1. 安装应用

- [下载最新版本及查看更新说明](https://github.com/1644ye/jingdu/releases/latest)
- [直接下载 1.4.1 APK](https://github.com/1644ye/jingdu/releases/download/v1.4.1/QuietReader-1.4.1.apk)
- [下载当前 main 分支源码 ZIP](https://github.com/1644ye/jingdu/archive/refs/heads/main.zip)

把 APK 下载到 Android 设备后打开，按系统提示安装；系统要求时，允许浏览器或文件管理器“安装未知应用”。更新现有安装时直接覆盖安装，保留原应用数据。

当前 APK 为非调试 Release 构建，沿用项目已有开发签名。后续可在“更多 → 软件更新”检查，支持每天自动检查和可选自动下载，安装仍需在系统页面确认。

## 2. 只在本机阅读，无需 GitHub

1. 下载下面的诗集／小说模板并填写，或直接下载完整示例试用，保存为 UTF-8 编码的 `.json` 文件。
2. 打开 App，点“更多 → 导入 JSON”，选择一个或多个文件。
3. 查看预览中的书名、数量、错误与警告，再确认导入。
4. 返回书架，点书籍进入详情，选择“开始阅读”。

本地导入、阅读、书架管理和备份都可以离线使用。当前跨设备进度同步面向从 GitHub 书源下载的书籍；需要同步的书请按后面的书源流程在各设备下载。

## 3. 直接下载模板和示例

“下载”链接直接指向 JSON 正文。浏览器显示文本时，可使用保存／下载功能并保留 `.json` 后缀；GitHub 文件页也有下载原始文件按钮。

| 文件 | 用途 | 直接下载 | 查看内容 |
| --- | --- | --- | --- |
| `poetry-template.json` | 填写自己的诗集 | [下载诗集模板](https://raw.githubusercontent.com/1644ye/jingdu/main/core/src/main/resources/protocol/poetry-template.json) | [查看模板](core/src/main/resources/protocol/poetry-template.json) |
| `novel-template.json` | 填写自己的小说 | [下载小说模板](https://raw.githubusercontent.com/1644ye/jingdu/main/core/src/main/resources/protocol/novel-template.json) | [查看模板](core/src/main/resources/protocol/novel-template.json) |
| `poetry-example.json` | 可直接导入试读的诗歌示例 | [下载诗歌示例](https://raw.githubusercontent.com/1644ye/jingdu/main/core/src/main/resources/protocol/poetry-example.json) | [查看示例](core/src/main/resources/protocol/poetry-example.json) |
| `novel-example.json` | 可直接导入试读的小说示例 | [下载小说示例](https://raw.githubusercontent.com/1644ye/jingdu/main/core/src/main/resources/protocol/novel-example.json) | [查看示例](core/src/main/resources/protocol/novel-example.json) |
| `catalog.json` | 与上面两份完整示例配套的 GitHub 书源索引 | [下载索引示例](https://raw.githubusercontent.com/1644ye/jingdu/main/docs/templates/catalog.json) | [查看索引](docs/templates/catalog.json) |
| 内容 JSON Schema | 使用外部工具校验结构 | [下载 Schema](https://raw.githubusercontent.com/1644ye/jingdu/main/core/src/main/resources/protocol/ebook-library-v1.schema.json) | [完整 JSON 协议](docs/JSON协议.md) |

App 内也有“更多 → 下载模板”入口。

填写正文时保留根节点的 `schemaVersion: 1` 和 `kind: "ebook-library"`。书籍和篇章、诗行、段落需要稳定且不重复的 ID；更新既有内容时保留原 ID，新增内容再使用新 ID。诗集模板包含逐行拼音、译文、注释，以及全诗背景、译文和赏析的完整填写示例；小说可使用卷、章和正文段落。

阅读诗集时点工具栏“辅助”，可分别设置拼音、逐行译文、注释、创作背景、全诗译文和赏析：隐藏、小字、正文大小、点击链接浮现。设置立即保存，可随时切回只显示原文；缺少的内容不会自动生成。目录显示“阅读中”和“已读”：翻到该篇最后一页或在滚动模式看到最后一行／段落末尾后记为已读，跳过的篇章保持未读。篇章记录进入本地备份；GitHub 仍只同步既有的书籍进度和阅读状态。

“辅助”页会显示本篇实际提供的内容数量。显示“本篇未提供”时，需要刷新书源并更新书籍正文，或重新导入含对应字段的 JSON；仅安装新版 APK 不会给旧正文补入拼音。全局阅读设置也使用相同的六类四种显示方式；已有单书设置的书籍可在阅读设置恢复为全局。[拼音与辅助内容排查步骤](docs/releases/v1.4.1.md)

**GitHub 书源要求一份 JSON 文件恰好一本书**；本地导入可以一份 JSON 包含多本。

### 书源最小示例

第一次配置，直接下载上表的 `poetry-example.json`、`novel-example.json` 和配套 `catalog.json`，按以下结构上传到自己的书源仓库：

```text
my-book-library/
├── README.md
├── catalog.json
└── books/
    ├── poetry-example.json
    └── novel-example.json
```

索引放在仓库根目录，两份书籍示例放在 `books` 目录。App 的“书源根目录”留空，“索引路径”填写 `catalog.json`，即可先完成一次下载验证。示例索引不能直接套到改过 ID 或路径的其他书籍上。

只使用 GitHub 网页时，也可以用“Add file → Create new file”，把文件名填写为 `books/poetry-example.json`，粘贴下载的示例 JSON 并提交；再创建 `books/novel-example.json`，最后在根目录创建 `catalog.json`。文件名中的 `/` 会建立目录，三个文件都要完成提交。[网页创建文件说明](https://docs.github.com/en/repositories/working-with-files/managing-files/creating-new-files)

### 添加自己的书并更新索引

按需要在书源仓库建立 `books/poetry/`、`books/novels/` 等目录，将自己的书籍 JSON 放入其中，再更新索引。

| 索引字段 | 填写方式 |
| --- | --- |
| `schemaVersion` | 数字 `1` |
| `libraryId` | 书库稳定标识，例如 `my-library`；用于更新与同步关联，确定后保持不变 |
| `books[].id` | 与正文中 `books[0].id` 完全一致，索引内不能重复 |
| `books[].path` | 相对书源根目录的路径，使用 `/`，例如 `books/novels/my-novel.json` |
| `books[].title`、`type` | 书名；类型为 `poetry` 或 `novel` |
| `author`、`tags`、`bytes` | 可选作者、标签和文件字节数；生成工具会填写 |

可以手动编辑索引，或在电脑上下载[索引生成脚本](https://raw.githubusercontent.com/1644ye/jingdu/main/tools/generate_catalog.py)，安装 Python 3 后运行：

```bash
python generate_catalog.py ./my-book-library --library-id my-library
```

`./my-book-library` 是电脑上的书源目录；生成的 `catalog.json` 位于该目录下。脚本仅在本机生成索引，接着由你通过 GitHub 网页上传，或使用 Git 提交推送。克隆了本项目时，脚本路径为 `tools/generate_catalog.py`。

书籍新增、删除、移动目录或修改信息后应重新生成索引，继续使用同一个 `--library-id`。App 只读取书源，书源文件和索引由你在 GitHub 或电脑上管理。

## 4. 创建自己的 GitHub 仓库

需要 GitHub 书源时准备一个仓库；需要跨设备进度同步时，再准备另一个仓库。

| 仓库 | 示例名称，可自定义 | 可见性 | 存放内容 | App 权限 |
| --- | --- | --- | --- | --- |
| 书源 | `my-book-library` | 公开或私有 | 书籍 JSON、`catalog.json` 和目录 | 只读 |
| 进度 | `my-reading-progress` | **必须私有** | 自动创建的 `progress/设备ID.json` | 读写 |

两个仓库必须不同，属于你的账号或你有权限使用的组织。项目本身的代码仓库不需要复制到书源或进度仓库。

1. 登录自己的 [GitHub 账号](https://github.com/login)；没有账号可以先[注册](https://github.com/signup)。
2. 打开[创建仓库页面](https://github.com/new)，选择仓库所属账号和自定义名称。
3. 创建书源仓库时选择可见性，勾选创建 README，完成创建。
4. 上传最小示例，或自己的书籍和索引。网页操作可用“Add file → Upload files”，提交保存后文件才会出现在仓库中。
5. 需要同步时再创建一个私有进度仓库，建议同样勾选 README，让仓库和默认分支先有一次提交；无需上传书籍或索引。

未初始化的空进度仓库也可连接，首次同步会创建进度文件；空书源则需要你先上传正文和索引，才能浏览和下载。[GitHub 创建仓库说明](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-new-repository)

## 5. 登录并连接书源

### 5.1 完成本人账号授权

1. 打开“更多 → GitHub 书库与进度同步 → 书源账号与仓库设置”。
2. 点“登录 / 更换账号”，等待 App 显示设备授权码。
3. 点“打开 GitHub 授权页面”，或打开 [GitHub 设备授权页](https://github.com/login/device)。
4. 确认浏览器登录的是目标账号，输入 App 显示的授权码并确认授权。需要换账号时，先在浏览器切换。
5. 回到 App，点“我已授权，等待确认”，等待返回设置页，核对“当前账号”。

书源和进度使用两套独立的 GitHub App，同步设置还需要再授权一次。可以使用同一个 GitHub 账号，但两套应用的授权和仓库权限分别管理。

### 5.2 给书源应用开放仓库访问

**本人登录授权与 GitHub App 安装到仓库是两个步骤。** 私有书源需要把书源应用安装到该仓库；“从已授权仓库选择”会从应用已安装的仓库中列出候选。[GitHub 官方说明](https://docs.github.com/en/apps/using-github-apps/installing-a-github-app-from-a-third-party)

打开对应书源 GitHub App 的安装页，选择自己的账号及书源仓库。书源应用的 `Contents` 权限应为只读。

- 已安装过：进入 [GitHub 已安装应用](https://github.com/settings/installations)，找到本次授权使用的书源应用，点“Configure”，把书源仓库加入访问范围。
- 查看授权过的登录应用：进入 [GitHub 已授权应用](https://github.com/settings/apps/authorizations)，核对设备授权使用的应用名称。[授权管理说明](https://docs.github.com/en/apps/using-github-apps/reviewing-and-revoking-authorization-of-github-apps)
- 尚未安装：从对应 GitHub App 的公开主页选择“Install”。使用现成 APK 时，需要登录应用维护者提供可公开安装的入口；只允许所有者安装的应用，需要维护者先开放范围。使用自己的登录应用，可按[自行部署](#11-自行构建与配置-github-登录应用)配置并编译。

### 5.3 填写连接信息并下载

在“书源账号与仓库设置”中点“从已授权仓库选择”，或手动填写：

| App 字段 | 填什么 |
| --- | --- |
| 仓库 URL | 可选，填 `https://github.com/你的账号/你的书源仓库`，使用仓库首页地址 |
| 仓库所有者 | 自己的 GitHub 用户名，或仓库所属组织名 |
| 仓库名称 | 例如 `my-book-library`，不包含账号和 `/` |
| 分支 | 留空使用默认分支；需要固定分支时填真实分支名 |
| 书源根目录 | 按最小示例配置时留空；书库位于某子目录时填该相对目录 |
| 索引路径 | 通常为 `catalog.json`，相对于书源根目录 |

URL 与手动所有者／仓库字段二选一填写即可；已填写 URL 时连接使用 URL 中的所有者和仓库。URL 不能包含 `/tree/main`、文件路径或其他附加页面地址。

点“连接并保存”后返回 GitHub 功能页，选择“浏览书源目录”，进入目录并勾选书籍，点“下载 / 更新所选”。下载完成后返回本地书架阅读。“下载任务”中可查看进度、暂停、继续、重试和取消。

“直接浏览真实目录（无需索引）”可查看仓库层次；当前书籍的批量选择、下载和更新使用索引页面，仍应准备 `catalog.json`。

## 6. 配置阅读进度双向同步

### 6.1 连接私有进度仓库

1. 在 GitHub 创建自己的私有进度仓库，例如 `my-reading-progress`。
2. 打开“更多 → GitHub 书库与进度同步 → 进度账号与仓库设置”。
3. 按设备授权步骤独立登录同步应用，核对当前账号。
4. 把同步 GitHub App 安装到私有进度仓库，授予 `Contents` 读写权限；可从 [GitHub 已安装应用](https://github.com/settings/installations)调整范围。
5. 从已授权仓库选择进度仓库，或填写所有者、仓库及分支，点“连接并保存”。
6. 返回“阅读进度同步”，开启“启用进度同步”，点“立即同步”。

首次同步会写入 `progress/设备ID.json`，进度仓库无需手动填写文件模板。该仓库必须私有，其中包含书名和文字定位所需信息。

### 6.2 两台设备怎么用

1. A、B 两台设备分别安装 App，完成本人账号授权。
2. 两端连接同一个书源、同一个私有进度仓库并启用同步，确认使用的账号都有相应仓库权限。
3. 两端从该书源下载同一本书，保持书源 `libraryId`、书籍 ID 与正文内部 ID 稳定。
4. A 阅读后返回书架，点继续阅读旁的“立即同步”，等到成功。
5. B 先点“立即同步”，再进入这本书核对恢复的位置。之后 B 阅读、同步，再到 A 同步，即为反向流程。

同步的是**阅读位置、阅读进度和阅读状态**，按正文 ID 与文字定位关联。书签、划线、笔记、个人标签、书架划分和配色目前保留在本机，可通过备份迁移。直接本地导入的书籍没有远端来源映射，不会自动加入进度同步。

启用后，App 在进入阅读前尝试同步，离开阅读时保存位置并申请后台同步；执行时间受 Android 调度和网络影响。切换设备前使用“立即同步”确认成功，便于核对结果。

### 6.3 离线和冲突

离线阅读先保存本机进度。两台设备离线分别阅读后再联网，可能出现多个候选位置；到“阅读进度同步 → 选择阅读位置”核对并选择，或采用本机当前阅读位置，再同步到其他设备。

正文版本不同且无法可靠定位时，先在本机打开书籍并选择正确章节、文字位置，再采用本机位置。App 不用简单的最后修改时间猜测离线冲突结果。

## 7. 更换账号、仓库与升级

**1.3.2 支持自动续期。升级后分别用原账号重新登录书源和同步角色一次，获取旧版未保存的刷新凭据。看到“已启用自动续期”后，日常请求会自动续期；仓库选择保留，进度角色重新登录后需确认开启同步。** 详见[自动续期与验收步骤](docs/1.3.2自动续期.md)。

- **覆盖升级**：保留已保存账号、仓库、正文和阅读记录；授权过期时重新登录。
- **同账号重新授权**：保留原仓库选择。
- **切换不同账号**：成功登录后清空该角色旧仓库选择及验证状态，需重新连接保存。书源旧缓存和未完成任务不再沿用，另一角色的登录及本地书籍保留。
- **主动退出账号**：移除该角色登录和仓库配置。
- **更换书源仓库**：保存新连接后，已下载书仍可本地阅读，新来源单独关联；当前一次连接一个书源。
- **更换同步账号／仓库或恢复备份**：同步暂停。确认目标仓库与本机进度后，再到同步页开启。

GitHub 登录令牌由 Android Keystore 加密保存在设备上，书库备份不包含令牌，新设备恢复后需重新授权。公开软件更新地址和登录应用 Client ID 是项目服务标识，不是使用者的书源或访问令牌。

## 8. 书架、标签与备份

| 操作 | 入口 |
| --- | --- |
| 新建／改名／排序／删除书架，重命名首页 | 更多 → 自定义书架 |
| 选择书架 | 首页底部点名称，或在书籍区域左右滑动 |
| 快速新增书架 | 首页底部“＋”，或“加入书架”弹窗 → 新建书架并加入 |
| 列表／网格显示 | 更多 → 列表显示／网格显示 |
| 控制单书在首页显示 | 书籍详情 → 在首页显示／从首页隐藏 |
| 全选、取消选择、批量归属／隐藏／标签／所选备份 | 更多 → 书架管理 → 批量操作 |
| 单书标签 | 书籍详情 → 标签／编辑信息 |
| 全库 ZIP 备份、JSON／ZIP 恢复 | 更多 → 备份与恢复 |
| 主题、HEX／RGB 颜色与预览 | 更多 → 应用配色 |
| 软件检查更新 | 更多 → 软件更新 |

一本书可以属于多个书架；从首页隐藏后仍能在所属书架和书架管理中找到。删除书架保留书籍。

**完整或所选备份的恢复都是整体替换，不是合并导入。** 所选备份只包含选中的书及相关记录，恢复前查看预览并备份重要数据。仅想添加书籍时，使用内容 JSON 导入。详见[备份与数据](docs/备份与数据.md)。

## 9. 更新远端书籍

1. 在 GitHub 网页或电脑上修改正文，保留既有内容的稳定 ID。
2. 更新 `catalog.json`，将正文和索引修改提交到书源仓库。
3. App 进入索引，点“刷新”，再“检查当前目录正文更新”。
4. 勾选需要更新的书籍，点“下载 / 更新所选”。

来源标签只读，个人标签可在本机维护。书籍从有效索引或来源目录移除后，本地版本会保留并显示来源移除标记。App 不提供上传、修改或删除 GitHub 书源文件的功能。

## 10. 常见问题

| 问题 | 优先检查 |
| --- | --- |
| 浏览前要求登录 | 先完成本人设备授权，再选择仓库并连接保存 |
| 已登录，但已授权仓库列表为空 | 是否给对应 GitHub App 安装并开放了目标仓库；两套应用分别配置 |
| 没有安装入口，或应用只允许所有者安装 | 联系应用维护者开放安装，或按下文用自己的 GitHub App 编译 |
| 提示未启用 Device Flow | 需要该应用维护者启用设备授权流程，再重新获取设备码 |
| 仓库／文件不存在或无访问权限 | 核对账号、URL、仓库、分支、根目录、索引路径及应用仓库授权 |
| 仓库暂无提交 | 给书源上传 JSON 与索引；进度仓库可先添加 README，或首次同步时初始化 |
| 列出书名但下载失败 | 索引路径对应的文件是否存在、是否一文件一本书、索引 ID 是否等于正文书籍 ID |
| 令牌过期、401 | 1.3.2 自动刷新并重试一次；旧版登录、刷新凭据过期或被撤销时，重新登录对应角色，保留仓库选择 |
| 同步已暂停 | 核对连接与本机位置后开启；更换连接及恢复备份后会暂停 |
| 两台设备没有关联进度 | 从同一书源下载同一本书、连接同一私有进度仓库；检查是否只是本地导入了同名书 |
| 网络超时／限流 | 确认设备可访问 GitHub，等待限制解除后重试；本地阅读仍可使用 |
| 后台没有立即下载／同步 | 检查网络和系统后台限制，重试下载或手动同步；强行停止后需重新打开 App |

## 11. 自行构建与配置 GitHub 登录应用

现成 APK 已内置登录应用 Client ID。**自行注册的 GitHub App，需要把自己的 Client ID 写入源码并重新构建，才会用于这份 APK 的登录。** 当前设置页支持换账号和仓库，未提供运行时更换 Client ID 的输入框。

### 11.1 创建自己的两套 GitHub App

打开 [GitHub App 创建页面](https://github.com/settings/apps/new)，为书源和同步各创建一套，使用各自唯一的名称。

| 设置 | 书源应用 | 同步应用 |
| --- | --- | --- |
| Homepage URL | 自己的项目主页 | 自己的项目主页 |
| Enable Device Flow | 开启 | 开启 |
| Repository permissions → Contents | Read-only | Read and write |
| Metadata | 保留 GitHub 必需的读取权限 | 保留 GitHub 必需的读取权限 |
| Webhook Active | 本项目不依赖 Webhook，可关闭 | 同左 |
| 安装范围 | 仅自己使用可选 Only on this account；给其他账号使用选 Any account | 同左 |
| 实际安装到的仓库 | 自己的书源仓库 | 自己的私有进度仓库 |

设备授权流程不依赖回调地址；保留令牌过期机制时，1.3.2 使用刷新令牌自动续期；刷新凭据失效后重新登录。创建后进入该应用“Install App”，安装到目标仓库，并复制各自的 **Client ID**。设置含义见 [GitHub 官方文档](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app)。

在 [GitHubConnection.kt](app/src/main/java/org/quietreader/remote/GitHubConnection.kt) 中更新 `GitHubApi` 的两个常量：

```kotlin
const val LIBRARY_CLIENT = "书源应用的Client ID"
const val STATE_CLIENT = "同步应用的Client ID"
```

设备授权路径使用公开 Client ID，无需在 APK 保存 Client Secret 或 GitHub App 私钥。用户令牌在本人授权后获取并保存在各自设备上。

### 11.2 获取源码和准备环境

```bash
git clone https://github.com/1644ye/jingdu.git
cd jingdu
```

也可以解压源码 ZIP，用 Android Studio 打开。开发环境需要 JDK 17 或更新版本、Android SDK 36；项目使用 Gradle 9.3.1、AGP 9.1.0，依赖通过 Gradle 下载。

设置本机 `JAVA_HOME`，在不提交到 Git 的 `local.properties` 中指定 SDK，例如：

```properties
sdk.dir=C:/Android/Sdk
```

把示例改为自己的 SDK 路径，在项目根目录构建：

```powershell
.\gradlew.bat :app:assembleRelease
```

macOS／Linux 使用 `./gradlew :app:assembleRelease`。输出为 `app/build/outputs/apk/release/app-release.apk`。Windows 也可用 `tools/build.ps1 -Tasks ':app:assembleRelease'`。

当前 Release 配置关闭调试，使用构建机器上的开发签名。自己的机器通常产生与项目发行包不同的签名；长期自行发行应配置并保管自己的固定发行签名，后续用同一签名覆盖升级。签名私钥不包含在仓库或源码 ZIP 中。

### 11.3 自行发布软件更新

更新源由 [ReleaseUpdates.kt](core/src/main/kotlin/org/quietreader/core/ReleaseUpdates.kt) 的 `REPOSITORY` 决定。自己分发时，将它改为自己的公开代码／发行仓库；它与用户选择的书源、进度仓库分别管理。

每版增加 `app/build.gradle.kts` 中的 `versionCode` 和三段式 `versionName`，用相同签名构建，创建正式 Release，标签如 `v1.3.2`，附加一个 APK。草稿及预发布版本不会作为自动更新目标。安装要求包名一致、版本号递增、签名相同。

### 11.4 开发验证

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

在专用模拟器安装应用和测试包后运行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.quietreader.test/android.test.InstrumentationTestRunner
```

1.4.1 的非调试 Release 已通过全部 96 项 Android 回归，包含六类辅助内容的48种阅读／显示组合和短诗切换后的原位恢复；43项核心测试复用通过结果，Release Lint 0错误／28警告。详情见 [1.4.1 验证记录](docs/1.4.1辅助内容显示.md)。Schema未改动，沿用1.4.0的22项独立检查结果。真实第三方账号授权、双设备联网同步及厂商后台行为仍需在相应设备验收，范围见[自动续期记录](docs/1.3.2自动续期.md)和[验收报告](ACCEPTANCE.md)。

## 更多资料

- [JSON 协议与文件大小限制](docs/JSON协议.md)
- [GitHub 登录与仓库配置](docs/GitHub登录配置.md)
- [备份格式与恢复说明](docs/备份与数据.md)
- [书架、软件更新与手机验收步骤](docs/1.3.0书架与更新.md)
- [1.4.0 辅助阅读与目录标记](docs/1.4.0书架与辅助阅读.md)
- [当前功能与待验证范围](docs/当前开发缺口.md)
- [实施状态](IMPLEMENTATION.md) · [需求范围](REQUIREMENTS.md) · [依赖说明](docs/依赖说明.md)

Git 历史已进行隐私清理，使用清理前克隆的维护者应重新克隆，再提交新修改。
