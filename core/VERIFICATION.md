# 核心协议验证记录

日期：2026-10-26。执行环境：Windows，本机 Android Studio 所带 Kotlin JVM 编译器 2.3.20、JDK 25.0.3，编译目标 JVM 17，Gson 2.11.0，JUnit 4.13.2。运行堆限制 `-Xmx512m`。

- 主源码编译通过。
- `ProtocolTest` 与 `AnchorsTest` 共 **31 个 JUnit 测试全部通过**，0 失败。最后一次测试报告耗时 8.193 秒。
- 核查包括原样用户示例、最简模板、BOM、纯文本和空格往返、未知字段清理、严格类型、重复 JSON 键、跨层级 ID、卷引用、Schema 版本、按类型约束、空内容、UTF-8 字节大小限制、嵌套深度、复制全部 ID 与引用、文字更新定位与模糊失效保护。
- `tools/ProtocolSmoke.kt` 对 4 份模板/示例和 3 份生成文件校验全部成功，均无警告。

桌面进程内一次解析观测（包含读取文件和完整校验，按执行顺序，非基准统计）：

| 文件 | 字节数 | 书籍数 | 时间 |
| --- | ---: | ---: | ---: |
| novel-example.json | 1,426 | 1 | 61 ms |
| novel-template.json | 451 | 1 | < 1 ms |
| poetry-example.json | 1,938 | 1 | 3 ms |
| poetry-template.json | 542 | 1 | < 1 ms |
| batch-10-books.json | 4,794 | 10 | 7 ms |
| novel-1000-3000000.json | 10,593,046 | 1 | 161 ms |
| poetry-1000.json | 1,013,283 | 1 | 39 ms |

这组结果仅证明纯 JVM 协议代码可处理这些数据，不能证明 Android 导入、数据库事务、界面响应、分页、搜索或恢复性能。首次解析包含 JVM 预热，不能与后续项目直接比较。未测峰值内存。

上述桌面记录使用已有编译器和 JUnit 运行器执行。2026-10-26 标准构建环境就绪后，已通过 Gradle `:core:test` 重跑相同31项测试，0失败、0错误（ProtocolTest 21项、AnchorsTest 10项），结果保存在 `artifacts/verification/`。两次不重复计数；实际Android设备与界面测量见根目录 `ACCEPTANCE.md`。生成性能输入使用 `python tools/generate_test_data.py`。
