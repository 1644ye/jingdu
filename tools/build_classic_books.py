"""Convert the downloaded public-domain source texts into Jingdu JSON v1."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'artifacts/book-sources'
OUTPUT = ROOT / 'artifacts/reading-library'


def save(name, book):
    destination = OUTPUT / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(dict(schemaVersion=1, kind='ebook-library', books=[book]), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return destination


def poetry():
    data = json.loads((SOURCE / 'tang300-canonical-source.json').read_text(encoding='utf-8-sig'))
    poems = []
    for item in data:
        identity = f"tang-{item['id']:03d}"
        lines = [dict(id=f'{identity}-line-{i:03d}', original=line.strip()) for i, line in enumerate(item['contents'].splitlines(), 1) if line.strip()]
        assert lines
        poems.append(dict(id=identity, title=item['title'], author=item['author'], dynasty='唐', genre=item['type'], stanzas=[dict(id=identity+'-stanza', lines=lines)]))
    assert len({p['id'] for p in poems}) == len(poems)
    path = save('books/诗集/唐诗三百首.json', dict(id='tang-poems-three-hundred', type='poetry', title='唐诗三百首', author='蘅塘退士 编选', language='zh-CN', tags=['唐诗', '古典文学'], description=f'据 xuchunyang/300 古诗原文数据整理，共{len(poems)}篇，依源文件拆分和顺序保留；不添加现代译注。来源：https://github.com/xuchunyang/300', poems=poems))
    print(f'Poetry: {len(poems)} poems, {path.stat().st_size} bytes')


def english():
    text = (SOURCE / 'monte-cristo-english-source.txt').read_text(encoding='utf-8-sig').replace('\r\n', '\n')
    markers = list(re.finditer(r'^ Chapter (\d+)\. (.+)$', text, re.MULTILINE))
    assert [int(m[1]) for m in markers] == list(range(1, 118)), 'Expected complete sequence of 117 chapters'
    end = text.index('*** END OF THE PROJECT GUTENBERG EBOOK', markers[-1].end())
    chapters = []
    for i, marker in enumerate(markers):
        number = int(marker[1])
        body = text[marker.end():markers[i+1].start() if i+1 < len(markers) else end]
        body = re.sub(r'^VOLUME (?:ONE|TWO|THREE|FOUR|FIVE)\s*$', '', body, flags=re.MULTILINE)
        body = re.sub(r'^\d{4}[a-z]?\s*$', '', body, flags=re.MULTILINE)
        paragraphs = [re.sub(r'\s*\n\s*', ' ', p.strip()) for p in re.split(r'\n\s*\n', body) if p.strip()]
        assert paragraphs
        chapters.append(dict(id=f'monte-chapter-{number:03d}', title=f'Chapter {number}. {marker[2]}', blocks=[dict(id=f'monte-c{number:03d}-p{j:04d}', type='paragraph', text=p) for j, p in enumerate(paragraphs, 1)]))
    path = save('books/小说/基督山伯爵-英文.json', dict(id='monte-cristo-english-pg1184', type='novel', title='基督山伯爵（英文全本）', author='Alexandre Dumas; Auguste Maquet', language='en', tags=['英文', '法国文学', '冒险'], description='The Count of Monte Cristo，Project Gutenberg #1184 英文版，117章。保留正文，移除电子书页眉页脚、目录重复项及插图占位编号；不是中文译本。来源：https://www.gutenberg.org/ebooks/1184', chapters=chapters))
    (OUTPUT / 'Project-Gutenberg-notice.txt').write_text(text[:text.index('*** START OF THE PROJECT GUTENBERG EBOOK')] + '\n' + text[end:], encoding='utf-8')
    print(f'English: {len(chapters)} chapters, {sum(len(c["blocks"]) for c in chapters)} paragraphs, {path.stat().st_size} bytes')


if __name__ == '__main__':
    OUTPUT.mkdir(parents=True, exist_ok=True)
    poetry()
    english()
