# 静读 JSON 内容协议（版本 1）

内容入口只接受 UTF-8 JSON，可带 UTF-8 BOM。字段名为英文；正文、标题与辅助文本支持中文。`core/src/main/resources/protocol/` 提供机器可校验的 `ebook-library-v1.schema.json`、两份最简模板和两份用户需求中的完整示例。

## 填写与导入

1. 在应用导入页面下载或复制诗集/小说模板。
2. 用文本编辑器填写。保留根节点 `schemaVersion: 1` 和 `kind: "ebook-library"`。
3. 为书籍与内部内容填写稳定 ID，保存为 UTF-8 `.json`。
4. 在应用选择一个或多个文件，查看校验、数量、警告及已有书籍冲突后确认导入。
5. 同 ID 表示同一书籍；默认跳过，用户可以明确选择更新或作为副本导入。

数组顺序就是阅读顺序，不能靠 ID 排序。所有正文均为纯文本；HTML 标签也按文字显示，不执行脚本。换行以 JSON 的 `\n` 表示，正文中的空格、换行、标点不会自动删改。必填内容不能是空串或只有空白；可选文本允许省略和空串，但不允许 `null`。

## 字段

| 对象 | 必填字段 | 可选字段 |
| --- | --- | --- |
| 根对象 | `schemaVersion` 数字 1、`kind` 固定 ebook-library、非空 `books` 数组 | 无 |
| 书籍 | `id`、`type`、`title`；诗集 `poems`，小说 `chapters` | `author`、`language`（省略默认 zh-CN）、`description`、`tags`；小说 `volumes` |
| 诗作 | `id`、`title`、非空 `stanzas` | `author`、`dynasty`、`genre`、`tags`、`background`、`translation`、`appreciation` |
| 诗节 | `id`、非空 `lines` | 无 |
| 诗行 | `id`、非空 `original` | `pinyin`、`translation`、`note` |
| 卷 | `id`、`title` | 无 |
| 章节 | `id`、`title`、非空 `blocks` | `volumeId` |
| 内容块 | `id`、`type` | `text`：paragraph 和 heading 必填非空；separator 可省略 |

书籍 type 仅接受 `poetry` 与 `novel`。诗集不能包含 `volumes` 或 `chapters`，小说不能包含 `poems`。内容块 type 仅接受 `paragraph`、`heading`、`separator`。`tags` 是字符串数组，允许空数组；实际正文数组必须非空。不分卷的小说可省略 `volumes` 和章节的 `volumeId`，提供 `volumeId` 时必须引用同书中存在的卷。

拼音是对应诗行的辅助文本，不承诺逐字对齐。没有提供的辅助字段由阅读界面隐藏。诗集模板已经填入《静夜思》的实际示例：行内 `pinyin`、`translation`、`note`，诗作内 `background`、`translation`、`appreciation`。辅助文本写在内容文件中，显示偏好保存在本地阅读设置中，不要把设置字段加进书籍 JSON。

阅读工具栏“辅助”分别管理六类内容，每项可选隐藏、小字显示、正文大小、点击链接浮现。链接显示为带下划线的标签，点击在可滚动弹窗里查看完整内容。隐藏模式不会因点击原文而展开；旧版设置仍保留点击诗行展开辅助内容的行为，明确选择显示方式后按新设置显示。更改显示方式不改变正文 ID、原文、书签和笔记坐标。

备份中的 `ReadingSettings` 可选增加 `pinyinDisplay`、`translationDisplay`、`notesDisplay`、`backgroundDisplay`、`poemTranslationDisplay`、`appreciationDisplay`。取值为 `legacy`、`hidden`、`small`、`inline`、`link`；省略按 `legacy` 处理，沿用旧版显示开关。篇章阅读历史存放在备份 `metadata` 中的 `remote:read:书籍ID`，值是序列化 JSON 字符串，篇章 ID 对应 `reading` 或 `read`。仅实际打开／到达末尾的篇章更新标记，不推断跳过的篇章已读；所选备份仅包含所选书籍的记录。

## ID 与更新

书籍 ID 在书库中唯一。书内的诗作、诗节、诗行、卷、章节和内容块共用一个 ID 命名空间，跨层级重复也会报错。不同书可以使用相同内部 ID。书籍 ID 与内部 ID 属于不同命名空间。建议使用 `my-book`、`poem-001`、`poem-001-line-01` 等易读字符串或 UUID。

调整顺序或修改文字时保留原内容 ID；新增内容使用新 ID。不要把已删除内容的 ID 转用于另一段文字。书名不是身份：同名不同 ID 的书可以共存。作为副本导入会重建书籍及全部内部 ID，并重建卷引用。

定位记录包含书籍、章节/诗作、正文块/诗行 ID、UTF-16 文本偏移、选中文字及前后文。偏移单位与 Android 文本选择一致，范围为 `[start,end)`。改字号与分页不更改正文坐标。更新时只在同一内容 ID 内重定位；原文未变则保留，原文变更时要求选文及前后文唯一可靠匹配，否则保留笔记并标记“原位置已变化”。不会到另一段重复文本猜测位置。

## 校验规则与限制

Schema 使用 JSON Schema Draft 2020-12。它描述字段、类型、必填规则及类型分支；应用的 `Protocol` 执行对应结构校验与额外语义校验。通用 Schema 校验器无法单独判断跨层级 ID 唯一性、卷引用、文件大小或取消事务，因此通过 Schema 不等于可直接入库。

未知字段允许在 Schema 中出现；应用预览逐条警告并忽略，确认后的书籍不会保存这些字段。错误路径例如 `books[0].poems[1].stanzas[0].lines[2].original`。同一对象的重复 JSON 键会拒绝，不能用重复键实现覆盖。语法错误保留解析器的行/列和路径信息。文件任一书籍存在错误时，整个文件不能导入；不同文件独立报告。

| 限制 | 上限 |
| --- | --- |
| 单个内容 JSON 文件 UTF-8 字节数 | 32 MiB（包括 BOM） |
| JSON 嵌套深度 | 根为第 0 层，最多 32 层 |
| 每文件 JSON 值节点 | 1,000,000 |
| 每文件书籍 | 1,000 |
| 单书诗作或章节、卷 | 各 10,000 |
| 单书内部对象（诗作/节/行/卷/章/块合计） | 200,000 |
| 单个文本 | 4,000,000 个 UTF-16 单元 |
| 单对象标签数 | 1,000 |
| 一次返回的错误、警告 | 各最多 200 条，修复后再次校验 |

Android 存储层还限制：单章/单首诗序列化 JSON 不超过 1 MiB，书籍元数据不超过 256 KiB，单条笔记不超过 128 KiB。超过时整个当前文件事务回滚；可将长章节拆成多章。批量选择最多 16 个文件，累计不超过 64 MiB；应用逐文件校验后暂存，避免同时保留所有大文件的正文对象。完整备份使用独立的 128 MiB 文件限制、48 层深度和 300 万节点限制。

超限请拆成多个文件或多本书。解析及校验为纯 JVM 工作，调用方应放后台线程；线程中断会抛出取消异常。`validate` 不修改输入对象，成功返回移除未知字段后的副本；失败返回空 document，防止部分有效内容误入库。

普通内容导出仍使用 `ebook-library`。完整备份由独立协议承载阅读记录、分组、笔记和设置，不能使用内容导入入口代替恢复备份。

## 性能数据生成

运行 `python tools/generate_test_data.py`，默认生成至 `test-data/generated/`：1000 首诗、1000 章且正文恰好 300 万中文字符的小说、10 本书批量文件及规模清单。生成数据不等于通过性能验收；必须在目标设备记录构建模式、耗时、内存和问题。
