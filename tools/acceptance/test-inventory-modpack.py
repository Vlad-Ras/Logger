#!/usr/bin/env python3
import hashlib, io, json, subprocess, sys, tempfile, zipfile
from pathlib import Path

script = Path(__file__).with_name('inventory-modpack.py')
with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary); mods = root / 'mods'; mods.mkdir(); output = root / 'inventory.json'
    def archive(entries):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as jar:
            for path, data in entries.items(): jar.writestr(path, data)
        return stream.getvalue()
    descriptor = '[[mods]]\nmodId="example"\nversion="${file.jarVersion}"\n[[dependencies.example]]\nmodId="minecraft"\nside="CLIENT"\n'
    first = archive({'META-INF/neoforge.mods.toml': descriptor,
                     'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\r\nImplementation-Version: 2.\r\n 5.0\r\n'})
    (mods / 'first.jar').write_bytes(first)
    (mods / 'duplicate.jar').write_bytes(archive({'META-INF/neoforge.mods.toml': '[[mods]]\nmodId="example"\nversion="${unresolved}"'}))
    (mods / 'client.jar').write_bytes(archive({'fabric.mod.json': json.dumps({'id': 'client', 'version': '1', 'environment': 'client'})}))
    (mods / 'broken.jar').write_bytes(b'not a zip')
    nested = archive({'META-INF/mods.toml': '[[mods]]\nmodId="embedded"\nversion="3"'})
    (mods / 'parent.jar').write_bytes(archive({'META-INF/jarjar/metadata.json': json.dumps({'jars': [{'path': 'META-INF/jarjar/library.jar', 'identifier': {'group': 'test', 'artifact': 'library'}, 'version': {'artifactVersion': '3'}}]}), 'META-INF/jarjar/library.jar': nested}))
    subprocess.run([sys.executable, str(script), str(mods), str(output)], check=True, capture_output=True)
    data = json.loads(output.read_text()); rows = {row['file']: row for row in data['jars']}
    assert rows['first.jar']['mods'][0]['version'] == '2.5.0'
    assert rows['first.jar']['mods'][0]['declared_environment'] == 'unknown'
    assert rows['first.jar']['sha256'] == hashlib.sha256(first).hexdigest()
    assert rows['duplicate.jar']['mods'][0]['unresolved_version'] == '${unresolved}'
    assert rows['client.jar']['mods'][0]['declared_environment'] == 'client'
    assert 'descriptor_error' in rows['broken.jar']
    assert len(data['duplicate_top_level_mod_ids']['example']) == 2
    assert rows['parent.jar']['nested_jars'][0]['sha256'] == hashlib.sha256(nested).hexdigest()
    assert rows['parent.jar']['nested_jars'][0]['mods'][0]['id'] == 'embedded'
    original = output.read_bytes()
    result = subprocess.run([sys.executable, str(script), str(mods), str(output)], capture_output=True)
    assert result.returncode != 0 and output.read_bytes() == original
print('MODPACK_INVENTORY_OK: exact hashes, manifest versions, unresolved placeholders, nested dependencies, collision and client-side evidence, malformed files and overwrite refusal')
