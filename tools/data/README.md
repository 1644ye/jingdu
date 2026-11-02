# 唐诗辅助内容

`tang-annotations.tsv` 按交付 JSON 的319篇原始顺序保存本项目编写的阅读背景和词语释义。背景依据诗题、原序及正文说明语境，不补造未经核实的作年；释义为简要阅读提示。

正文来源、篇目校对参考公版原作：[唐詩三百首](https://zh.wikisource.org/wiki/唐詩三百首)。缺字校正为媕、窅、箓、菑，个别作者姓名恢复为完整字形；保留书籍、诗作、诗行 ID。原来源《杂诗》的“尽寒食雨”保留版本用字。

`tools/PoetryPinyin.java` 使用已安装 Android 的 `android.icu.text.Transliterator`（Han-Latin）生成现代普通话拼音，并对常见多音词、地名、人名和诗词异读作词组覆盖。它仅用于制作内容，不是 App 的依赖。生成拼音后运行：

```text
python tools/enrich_tang_annotations.py input-with-pinyin.json output.json
```

拼音是阅读辅助，不是古音重建；未完成逐字人工校勘。内容校订可以继续修正词组表和注释，保留稳定 ID 后重新生成／更新正文。两部小说不经过此工具。

现有 Android SDK 可将 Java 源码用 `javac --release 17 -cp android.jar` 编译，再用 `d8` 生成 JAR；推送至测试模拟器，通过 `CLASSPATH=工具.jar app_process /data/local/tmp PoetryPinyin 输入.json 输出.json` 执行。无需安装额外 Python 或 Android 库。
