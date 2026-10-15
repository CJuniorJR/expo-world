"""Verify the installed APK contains exactly the assets requested by the loader.

AAPT expands .gz input assets and strips the extension, so source-file checks
alone missed the 0.3.0 failure. This check inspects the actual deliverable.
"""
import json
from pathlib import Path
import re
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'app/build/outputs/apk/debug/app-debug.apk'
with zipfile.ZipFile(apk) as package:
    required = set()
    for source in ['storage/CuritibaRepository.kt', 'ui/WorldMap.kt']:
        text = (root / 'app/src/main/java/br/com/expoworld' / source).read_text(encoding='utf-8')
        required.update(re.findall(r'assets.open\("([^"\n]+)"\)', text))
    assert required == {'curitiba-network.json', 'curitiba-neighborhoods.geojson'}, required
    for name in required:
        bundled = package.read('assets/' + name)
        assert bundled == (root / 'app/src/main/assets' / name).read_bytes(), name
        json.loads(bundled)
    network = json.loads(package.read('assets/curitiba-network.json'))
    boundaries = json.loads(package.read('assets/curitiba-neighborhoods.geojson'))
    assert len(network['roads']) == 43655 and len(boundaries['features']) == 75
    assert network['metadata']['version'] == 'curitiba-167e3e15fb46'
print('PASS: actual APK assets match loader names and source bytes; 75 neighborhoods, 43,655 streets, existing dataset identity preserved.')
