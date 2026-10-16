"""Checks the shipped catalog and the published per-neighborhood totals (no network/GPS)."""
import json
import math
from pathlib import Path

root = Path(__file__).resolve().parent.parent
assets = root / 'app/src/main/assets'
with (assets / 'curitiba-network.json').open(encoding='utf-8') as stream:
    catalog = json.load(stream)
boundaries = json.loads((assets / 'curitiba-neighborhoods.geojson').read_text(encoding='utf-8'))
report = json.loads((root / 'data/curitiba/totals.json').read_text(encoding='utf-8'))
assert len(boundaries['features']) == len(catalog['neighborhoods']) == 75
ids = {feature['properties']['id'] for feature in boundaries['features']}
assert ids == {n['id'] for n in catalog['neighborhoods']}
assert all(n.startswith('4106902') for n in ids)
assert catalog['metadata'] == report['metadata']
assert catalog['metadata']['license'] == 'ODbL-1.0'
totals = {n: [0.0, 0.0, 0.0, 0.0] for n in ids}
street_ids, geometries = set(), set()
for street_id, bairro, modes, coords in catalog['roads']:
    assert street_id not in street_ids and bairro in ids and 1 <= modes <= 7
    street_ids.add(street_id)
    points = tuple(tuple(p) for p in coords)
    key = min(points, tuple(reversed(points)))
    assert key not in geometries
    geometries.add(key)
    assert len(coords) >= 2 and all(math.isfinite(v) and abs(v) < 40_000 for p in coords for v in p)
    length = sum(math.dist(a, b) for a, b in zip(coords, coords[1:]))
    assert length > 0
    totals[bairro][3] += length / 1000
    for index, bit in enumerate([1, 2, 4]):
        if modes & bit: totals[bairro][index] += length / 1000
assert len(street_ids) == report['roadParts']
for row in report['neighborhoods']:
    for index, key in enumerate(['carKm', 'walkKm', 'bikeKm', 'sharedKm']):
        assert abs(totals[row['id']][index] - row[key]) <= .00051
print('PASS:', len(ids), 'IBGE neighborhoods,', len(street_ids), 'unique road parts; finite geometry, ownership, modes, version and per-neighborhood totals agree.')
print('Estimated city km: car=%.3f walk=%.3f bike=%.3f shared=%.3f' % tuple(sum(v[i] for v in totals.values()) for i in range(4)))
