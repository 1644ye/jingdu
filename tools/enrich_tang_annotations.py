"""Apply reviewed reading notes to a Tang JSON already containing line-level pinyin.

Usage: python tools/enrich_tang_annotations.py input-with-pinyin.json output.json
The Android ICU conversion is a content-generation step, not an app dependency.
"""
import argparse
import json
from pathlib import Path


def enrich(source: Path, target: Path):
    document = json.loads(source.read_text(encoding="utf-8-sig"))
    book = document["books"][0]
    annotations = {}
    for row in (Path(__file__).parent / "data/tang-annotations.tsv").read_text(encoding="utf-8").splitlines():
        if not row or row.startswith("#"):
            continue
        number, background, definitions = row.split("|", 2)
        annotations[int(number)] = (background, [item.split("=", 1) for item in definitions.split(";")])
    assert len(book["poems"]) == len(annotations) == 319
    for index, poem in enumerate(book["poems"], 1):
        background, definitions = annotations[index]
        poem["background"] = background
        poem.setdefault("translation", "")
        poem.setdefault("appreciation", "")
        lines = [line for stanza in poem["stanzas"] for line in stanza["lines"]]
        for line in lines:
            assert line.get("pinyin", "").strip(), (index, line["id"])
            line["note"] = "；".join(f"{term}：{meaning}" for term, meaning in definitions if term in line["original"])
            line.setdefault("translation", "")
        for term, meaning in definitions:
            if term in poem["title"] and not any(term in line["original"] for line in lines):
                lines[0]["note"] = "；".join(filter(None, (lines[0]["note"], f"题中“{term}”：{meaning}")))
        assert any(line["note"] for line in lines), (index, poem["title"])
    book["description"] = "唐诗选集319篇，保留来源篇目、顺序和稳定ID。附现代普通话带调拼音、词语注释及据诗题、原序和正文整理的写作语境；不附会未证实的作年。词语注释为本项目补注，拼音含常见诗词异读校正，古今异读仍可参看注释。"
    book["tags"] = list(dict.fromkeys(book.get("tags", []) + ["拼音", "注释", "背景"]))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Enriched 319 poems with pinyin, notes and reading context")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    enrich(args.source, args.target)
