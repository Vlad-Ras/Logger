#!/usr/bin/env python3
"""Write a reproducible mod inventory without executing jars or reading server secrets."""
import argparse,hashlib,json,zipfile,tomllib
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('mods',type=Path);p.add_argument('output',type=Path)
a=p.parse_args()
if not a.mods.is_dir():p.error('mods must be an existing directory')
if a.output.exists():p.error('output already exists; use a new file')
rows=[]
for path in sorted(a.mods.glob('*.jar')):
    digest=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''):digest.update(block)
    row={'file':path.name,'bytes':path.stat().st_size,'sha256':digest.hexdigest(),'mods':[]}
    try:
        with zipfile.ZipFile(path) as jar:
            for name in ('META-INF/neoforge.mods.toml','META-INF/mods.toml','fabric.mod.json'):
                if name not in jar.namelist():continue
                member=jar.getinfo(name)
                if member.file_size>2*1024*1024:raise ValueError('oversized mod descriptor')
                text=jar.read(name).decode('utf-8-sig')
                if name.endswith('.json'):
                    data=json.loads(text);row['mods'].append({'id':data.get('id'),'version':data.get('version'),'descriptor':name})
                else:
                    for mod in tomllib.loads(text).get('mods',[]):row['mods'].append({'id':mod.get('modId'),'version':mod.get('version'),'descriptor':name})
    except (OSError,zipfile.BadZipFile,UnicodeError,ValueError) as error:row['descriptor_error']=str(error)
    rows.append(row)
a.output.parent.mkdir(parents=True,exist_ok=True)
with a.output.open('x') as output:json.dump({'schema':1,'jar_count':len(rows),'jars':rows},output,ensure_ascii=False,indent=2)
print(f'Inventoried {len(rows)} jars: {a.output}')
