"""Generate catalog.json locally; never uploads or changes a GitHub repository."""
import argparse
import json
from pathlib import Path


def generate(root, library_id, output):
    root = root.resolve()
    output = output.resolve()
    books = []
    ids = set()
    for file in sorted(root.rglob('*.json')):
        if file.resolve() == output:
            continue
        document = json.loads(file.read_text(encoding='utf-8-sig'))
        if document.get('kind') != 'ebook-library':
            continue
        items = document.get('books', [])
        if len(items) != 1:
            raise ValueError(f'{file}: 远程书库要求一本书一个文件')
        book = items[0]
        identity = book.get('id', '')
        if not identity or identity in ids:
            raise ValueError(f'{file}: 书籍 ID 为空或重复')
        if not book.get('title') or book.get('type') not in ('poetry', 'novel'):
            raise ValueError(f'{file}: 书名或类型无效')
        if file.stat().st_size > 32 * 1024 * 1024:
            raise ValueError(f'{file}: 超过 32 MiB')
        ids.add(identity)
        books.append(dict(id=identity, path=file.relative_to(root).as_posix(),
                          title=book['title'], author=book.get('author', ''),
                          type=book['type'], tags=book.get('tags', []), bytes=file.stat().st_size))
    result = dict(schemaVersion=1, libraryId=library_id, books=books)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--library-id', required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    generate(args.root, args.library_id, args.output or args.root / 'catalog.json')
