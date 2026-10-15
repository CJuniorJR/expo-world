"""Reproducible offline Curitiba package. Inputs: IBGE PR bairros CD2022 + bounded Overpass query.
Install shapely and pyshp under .tools/geodata-libs. No GPS records or credentials are used.
"""
import hashlib
import json
import math
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / '.tools/geodata-libs'))
import shapefile
from shapely.geometry import shape, LineString, mapping
from shapely.ops import transform, unary_union
from shapely.strtree import STRtree

RAW = ROOT / '.tools/geodata'
OUT = ROOT / 'app/src/main/assets'
OUT.mkdir(parents=True, exist_ok=True)
SCALE_X = 6378137 * math.pi / 180 * math.cos(math.radians(-25.48))
SCALE_Y = 6378137 * math.pi / 180
def project(x, y, z=None):
    return (x + 49.28) * SCALE_X, (y + 25.48) * SCALE_Y
def unproject(x, y, z=None):
    return x / SCALE_X - 49.28, y / SCALE_Y - 25.48

folder = RAW / 'ibge'
folder.mkdir(exist_ok=True)
with zipfile.ZipFile(RAW / 'bairros.zip') as archive:
    for member in archive.infolist():
        target = (folder / member.filename).resolve()
        assert target.is_relative_to(folder.resolve())
    archive.extractall(folder)
reader = shapefile.Reader(str(next(folder.rglob('*.shp'))), encoding='utf-8')
features = []
for item in reader.iterShapeRecords():
    record = item.record.as_dict()
    if str(record.get('CD_MUN', '')) != '4106902':
        continue
    geometry = shape(item.shape.__geo_interface__).buffer(0)
    assert -50 < geometry.bounds[0] < -48 and -27 < geometry.bounds[1] < -24, 'Expected SIRGAS2000 geographic coordinates'
    features.append({'type': 'Feature', 'properties': {'id': str(record['CD_BAIRRO']), 'name': record['NM_BAIRRO']}, 'geometry': mapping(geometry)})
features.sort(key=lambda f: f['properties']['id'])
assert len(features) >= 70, f'Incomplete IBGE bairros: {len(features)}'
polygons = [transform(project, shape(f['geometry'])) for f in features]
tree = STRtree(polygons)
city = unary_union(polygons)

def modes(tags):
    kind = tags.get('highway', '')
    streets = {'motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link', 'secondary', 'secondary_link', 'tertiary', 'tertiary_link', 'unclassified', 'residential', 'living_street', 'service', 'road', 'track'}
    paths = {'footway', 'pedestrian', 'path', 'steps', 'cycleway'}
    if kind not in streets | paths or tags.get('area') == 'yes' or tags.get('footway') in {'sidewalk', 'crossing'}:
        return 0
    blocked = {'no', 'private', 'customers', 'delivery', 'destination', 'permit'}
    if tags.get('access') in blocked:
        return 0
    car = kind in streets and tags.get('motor_vehicle', tags.get('vehicle', 'yes')) not in blocked
    walk = kind not in {'motorway', 'motorway_link', 'trunk', 'trunk_link'}
    bike = walk and kind != 'steps'
    if kind == 'cycleway':
        walk = tags.get('foot') in {'yes', 'designated', 'permissive'}
    if tags.get('foot') in blocked: walk = False
    if tags.get('bicycle') in blocked: bike = False
    if tags.get('foot') in {'yes', 'designated', 'permissive'}: walk = True
    if tags.get('bicycle') in {'yes', 'designated', 'permissive'}: bike = True
    return int(car) | int(walk) * 2 | int(bike) * 4

def lines(geometry):
    if geometry.is_empty: return []
    if geometry.geom_type == 'LineString': return [geometry]
    if hasattr(geometry, 'geoms'): return [g for part in geometry.geoms for g in lines(part)]
    return []

raw_roads = json.loads((RAW / 'roads.json').read_text(encoding='utf-8'))
assert not raw_roads.get('remark'), raw_roads.get('remark')
roads, seen = [], set()
for way in sorted(raw_roads['elements'], key=lambda w: w['id']):
    allowed = modes(way.get('tags', {}))
    if not allowed or len(way.get('geometry', [])) < 2: continue
    original = transform(project, LineString([(p['lon'], p['lat']) for p in way['geometry']]))
    if not original.intersects(city): continue
    remaining = original.intersection(city)
    # Each physical piece has ONE owner, including a road exactly on a boundary.
    for index in sorted(tree.query(original).tolist()):
        intersected = remaining.intersection(polygons[index])
        for part_index, line in enumerate(lines(intersected)):
            if line.length < 1.0: continue
            coords = [(round(x, 2), round(y, 2)) for x, y in line.coords]
            canonical = min(tuple(coords), tuple(reversed(coords)))
            if canonical in seen: continue
            seen.add(canonical)
            roads.append([f"{way['id']}:{index}:{part_index}", features[index]['properties']['id'], allowed, coords])
        remaining = remaining.difference(polygons[index])
        if remaining.is_empty: break
assert len(roads) > 1000, 'Road package is unexpectedly incomplete'
digest = hashlib.sha256((RAW / 'roads.json').read_bytes() + (RAW / 'bairros.zip').read_bytes() + Path(__file__).read_bytes()).hexdigest()[:12]
metadata = {'version': 'curitiba-' + digest, 'retrieved': '2026-10-15', 'boundaries': 'IBGE Censo 2022 (31/07/2022)', 'roadsTimestamp': raw_roads.get('osm3s', {}).get('timestamp_osm_base', 'unknown'), 'license': 'ODbL-1.0', 'attribution': '© OpenStreetMap contributors; limites: IBGE, Censo 2022', 'binMeters': 10}
package = {'metadata': metadata, 'neighborhoods': [{'id': f['properties']['id'], 'name': f['properties']['name']} for f in features], 'roads': roads}
# AAPT expands .gz assets and removes the suffix; ship JSON, compressed by the APK ZIP.
with (OUT / 'curitiba-network.json').open('w', encoding='utf-8') as stream:
    json.dump(package, stream, ensure_ascii=False, separators=(',', ':'))
(OUT / 'curitiba-neighborhoods.geojson').write_text(json.dumps({'type': 'FeatureCollection', 'features': features}, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
totals = {f['properties']['id']: {'name': f['properties']['name'], 'carKm': 0.0, 'walkKm': 0.0, 'bikeKm': 0.0, 'sharedKm': 0.0} for f in features}
for _, bairro, allowed, coords in roads:
    km = LineString(coords).length / 1000
    totals[bairro]['sharedKm'] += km
    for bit, key in [(1, 'carKm'), (2, 'walkKm'), (4, 'bikeKm')]:
        if allowed & bit: totals[bairro][key] += km
report = {'metadata': metadata, 'roadParts': len(roads), 'neighborhoods': [{**{'id': key}, **{k: round(v, 3) if isinstance(v, float) else v for k, v in row.items()}} for key, row in totals.items()]}
(ROOT / 'data/curitiba').mkdir(parents=True, exist_ok=True)
(ROOT / 'data/curitiba/totals.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Prepared', len(features), 'neighborhoods,', len(roads), 'road parts;', metadata['version'])
