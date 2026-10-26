"""Generate deterministic offline acceptance fixtures without third-party packages."""
import argparse
import json
from pathlib import Path


def document(books):
    return {"schemaVersion": 1, "kind": "ebook-library", "books": books}


def write(path, value):
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, separators=(",", ":"))
        stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="test-data/generated")
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    poems = []
    for i in range(1000):
        poems.append({
            "id": f"poem-{i}", "title": f"测试诗作 {i + 1}", "author": "测试生成器",
            "dynasty": "当代", "genre": "测试诗", "tags": ["性能测试"],
            "background": "本篇为程序生成的测试资料。", "appreciation": "测试辅助内容展开与重新排版。",
            "stanzas": [
                {"id": f"stanza-{i}-{s}", "lines": [
                    {"id": f"line-{i}-{s}-{n}", "original": line,
                     "pinyin": pinyin, "translation": "用于验证诗行辅助内容显示。", "note": "测试注释"}
                    for n, (line, pinyin) in enumerate([
                        ("青山映远水，", "qīng shān yìng yuǎn shuǐ"),
                        ("晚风入书窗。", "wǎn fēng rù shū chuāng"),
                    ])
                ]} for s in range(2)
            ],
        })
    poetry = {"id": "stress-poetry-1000", "type": "poetry", "title": "千首诗测试集", "tags": ["测试数据"], "poems": poems}
    write(output / "poetry-1000.json", document([poetry]))

    # Every paragraph contains exactly 100 CJK characters, without punctuation or ASCII.
    seed = "山城雨后清风穿过旧书店的窗沿纸页留下细微光影少年沿着石阶走向远方家书静静躺在桌上"
    paragraph = (seed * 4)[:100]
    chapters = []
    for i in range(1000):
        chapters.append({"id": f"chapter-{i}", "volumeId": f"volume-{i // 100}", "title": f"第 {i + 1} 章",
                         "blocks": [{"id": f"block-{i}-{j}", "type": "paragraph", "text": paragraph} for j in range(30)]})
    novel = {"id": "stress-novel-3000000", "type": "novel", "title": "三百万字测试小说", "tags": ["测试数据"],
             "volumes": [{"id": f"volume-{i}", "title": f"第 {i + 1} 卷"} for i in range(10)], "chapters": chapters}
    write(output / "novel-1000-3000000.json", document([novel]))
    batch = [{"id": f"batch-book-{i}", "type": "novel", "title": f"批量测试书 {i + 1}",
              "chapters": [{"id": "chapter-1", "title": "第一章", "blocks": [{"id": "block-1", "type": "paragraph", "text": paragraph}]}]}
             for i in range(10)]
    write(output / "batch-10-books.json", document(batch))
    count = sum(len(block["text"]) for chapter in chapters for block in chapter["blocks"])
    assert count == 3_000_000
    manifest = {"poems": 1000, "chapters": 1000, "novelChineseCharacters": count, "batchBooks": 10,
                "files": {path.name: path.stat().st_size for path in sorted(output.glob("*.json")) if path.name != "manifest.json"},
                "performanceMeasured": False}
    write(output / "manifest.json", manifest)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
