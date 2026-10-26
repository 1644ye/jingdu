"""Validate distributable JSON Schemas and fixtures using the independent JSON Schema engine."""
import copy
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools' / 'schema-check'))
from jsonschema import Draft202012Validator

resources = ROOT / 'core/src/main/resources/protocol'
schema = json.loads((resources / 'ebook-library-v1.schema.json').read_text(encoding='utf-8'))
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)
passed = 0
for name in ('poetry-template', 'novel-template', 'poetry-example', 'novel-example'):
    doc = json.loads((resources / (name + '.json')).read_text(encoding='utf-8-sig'))
    validator.validate(doc)
    passed += 1
novel = json.loads((resources / 'novel-example.json').read_text(encoding='utf-8-sig'))
for mutation in ('version', 'type', 'text', 'null', 'empty'):
    broken = copy.deepcopy(novel)
    book = broken['books'][0]
    if mutation == 'version': broken['schemaVersion'] = 2
    if mutation == 'type': book['type'] = 'poetry'
    if mutation == 'text': del book['chapters'][0]['blocks'][0]['text']
    if mutation == 'null': book['author'] = None
    if mutation == 'empty': book['chapters'] = []
    assert not validator.is_valid(broken), mutation
    passed += 1
unknown = copy.deepcopy(novel)
unknown['books'][0]['vendorExtra'] = {'ignored': True}
validator.validate(unknown)  # Warning and stripping are intentionally application semantics.
passed += 1
for path in sorted((ROOT / 'test-data/generated').glob('*.json')):
    if path.name == 'manifest.json': continue
    doc = json.loads(path.read_text(encoding='utf-8'))
    if doc.get('kind') == 'ebook-library':
        validator.validate(doc)
        passed += 1
backup_schema = resources / 'ebook-backup-v1.schema.json'
if backup_schema.exists():
    Draft202012Validator.check_schema(json.loads(backup_schema.read_text(encoding='utf-8')))
    passed += 1
print(f'Independent JSON Schema checks passed: {passed}')
