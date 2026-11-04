#!/usr/bin/env python3
"""Use the exact official 6.0.10 distribution when the development Maven is unavailable.

TLS verification stays enabled. The immutable Modrinth release and all its bundled
libraries are checked against the release's published SHA-512 before extraction.
These compile/runtime inputs are never bundled into AvilixLogger.
"""
import hashlib
import pathlib
import urllib.request
import zipfile

URL = 'https://cdn.modrinth.com/data/LNytGWDc/versions/UjX6dr61/create-1.21.1-6.0.10.jar'
SHA512 = '11cc8fc049d2f67f6548c7abfada6b82a3adb5c7ca410a742de04bbca76e03862c518721b88d806f6e6d768a4d68531fdb903a85859b25d1484d550cc7bafd4b'
ROOT = pathlib.Path('build/compat-create-release')
ROOT.mkdir(parents=True, exist_ok=True)
archive = ROOT / 'create-1.21.1-6.0.10.jar'
if not archive.exists() or hashlib.sha512(archive.read_bytes()).hexdigest() != SHA512:
    with urllib.request.urlopen(URL, timeout=90) as response:
        content = response.read()
    if hashlib.sha512(content).hexdigest() != SHA512:
        raise ValueError('Official Create release SHA-512 mismatch')
    archive.write_bytes(content)
with zipfile.ZipFile(archive) as jar:
    for entry in jar.namelist():
        if entry.startswith('META-INF/jarjar/') and entry.endswith('.jar'):
            (ROOT / pathlib.PurePosixPath(entry).name).write_bytes(jar.read(entry))
print('CREATE_RELEASE_INPUT_OK: official 6.0.10, published SHA-512, bundled libraries; no TLS bypass')
