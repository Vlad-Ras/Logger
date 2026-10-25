#!/usr/bin/env python3
"""Inventory exact server/client JAR bytes without executing mods or reading server secrets."""
import argparse, hashlib, io, json, zipfile, tomllib
from pathlib import Path

MAX_DESCRIPTOR = 2 * 1024 * 1024
MAX_NESTED_JAR = 64 * 1024 * 1024
MAX_NESTED_COUNT = 256

def manifest_version(jar):
    try:
        if jar.getinfo('META-INF/MANIFEST.MF').file_size > MAX_DESCRIPTOR:
            raise ValueError('oversized manifest')
        raw = jar.read('META-INF/MANIFEST.MF').decode('utf-8-sig')
        logical = raw.replace('\r\n', '\n').replace('\n ', '')
        fields = dict(line.split(': ', 1) for line in logical.splitlines() if ': ' in line)
        return fields.get('Implementation-Version') or fields.get('Bundle-Version')
    except (KeyError, UnicodeError, ValueError):
        return None

def describe(jar):
    mods = []
    version = manifest_version(jar)
    for name in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml', 'fabric.mod.json'):
        if name not in jar.namelist():
            continue
        member = jar.getinfo(name)
        if member.file_size > MAX_DESCRIPTOR:
            raise ValueError('oversized mod descriptor')
        text = jar.read(name).decode('utf-8-sig')
        if name.endswith('.json'):
            data = json.loads(text)
            mods.append({'id': data.get('id'), 'version': data.get('version'),
                         'descriptor': name, 'declared_environment': data.get('environment', '*')})
        else:
            for mod in tomllib.loads(text).get('mods', []):
                raw = mod.get('version')
                resolved = version if raw == '${file.jarVersion}' else raw
                row = {'id': mod.get('modId'), 'version': resolved, 'descriptor': name,
                       'declared_environment': 'unknown'}
                # Dependency side/displayTest is not evidence that the mod is exclusively client-side.
                if isinstance(resolved, str) and '${' in resolved or raw == '${file.jarVersion}' and not version:
                    row['unresolved_version'] = raw
                mods.append(row)
    return mods

def inventory(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    row = {'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest.hexdigest(), 'mods': [], 'nested_jars': []}
    try:
        with zipfile.ZipFile(path) as jar:
            row['mods'] = describe(jar)
            if 'META-INF/jarjar/metadata.json' in jar.namelist():
                if jar.getinfo('META-INF/jarjar/metadata.json').file_size > MAX_DESCRIPTOR:
                    raise ValueError('oversized nested dependency descriptor')
                metadata = json.loads(jar.read('META-INF/jarjar/metadata.json'))
                entries = metadata.get('jars', [])
                if len(entries) > MAX_NESTED_COUNT:
                    raise ValueError('too many nested dependencies')
                total = 0
                for entry in entries:
                    name = entry['path']
                    member = jar.getinfo(name)
                    total += member.file_size
                    if total > MAX_NESTED_JAR:
                        raise ValueError('nested dependency inventory exceeds 64 MiB')
                    data = jar.read(name)
                    nested = {'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                              'coordinate': entry.get('identifier'), 'version': entry.get('version'), 'mods': []}
                    with zipfile.ZipFile(io.BytesIO(data)) as dependency:
                        nested['mods'] = describe(dependency)
                    row['nested_jars'].append(nested)
    except (OSError, zipfile.BadZipFile, UnicodeError, ValueError, KeyError, TypeError) as error:
        row['descriptor_error'] = str(error)
    return row

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mods', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if not args.mods.is_dir():
        parser.error('mods must be an existing directory')
    if args.output.exists():
        parser.error('output already exists; use a new file')
    rows = [inventory(path) for path in sorted(args.mods.glob('*.jar'))]
    by_id = {}
    for row in rows:
        for mod in row['mods']:
            if mod.get('id'):
                by_id.setdefault(mod['id'], []).append(row['file'])
    collisions = {key: files for key, files in by_id.items() if len(set(files)) > 1}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x') as output:
        json.dump({'schema': 2, 'jar_count': len(rows), 'jars': rows, 'duplicate_top_level_mod_ids': collisions},
                  output, ensure_ascii=False, indent=2)
    print(f'Inventoried {len(rows)} jars; duplicate top-level IDs: {len(collisions)}; output: {args.output}')

if __name__ == '__main__':
    main()
