"""Exercise the real SQL normalizer against every shipped schema from both branches.
Host SQLite checks do not replace Android/Room device tests.
"""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parent.parent
schemas = root / 'app/schemas/br.com.expoworld.storage.ExplorationDatabase'
source = (root / 'app/src/main/java/br/com/expoworld/storage/ExplorationDatabase.kt').read_text(encoding='utf-8')
normalizer = (root / 'app/src/main/java/br/com/expoworld/storage/UnifiedMigration.kt').read_text(encoding='utf-8')
expected = json.loads((schemas / '8.json').read_text(encoding='utf-8'))['database']
create = re.findall(r'db.execSQL\("(CREATE [^"\n]+)"\)', normalizer)
columns = re.findall(r'ensureColumn\("([^"\n]+)", "([^"\n]+)", "([^"\n]+)"\)', normalizer)
assert len(create) == 5 and len(columns) == 5
migrations = {int(start): re.findall(r'db.execSQL\("([^"\n]+)"\)', body)
    for start, body in re.findall(r'val MIGRATION_(\d+)_\d+ = object.*?override fun migrate.*?\{(.*?)\n    }\n}', source, re.S)}
assert set(migrations) == {1, 2}
queries = re.findall(r'@Query\("([^"\n]+)"\)', source)
def table_names(db): return {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
def names(db, table): return {r[1] for r in db.execute('PRAGMA table_info('+table+')')}
def normalize(db):
    for sql in create: db.execute(sql)
    for table, column, definition in columns:
        if column not in names(db,table): db.execute('ALTER TABLE '+table+' ADD COLUMN '+column+' '+definition)

versions = [(str(v), schemas / (str(v)+'.json')) for v in range(1,8)]
versions += [('home-'+str(v), root / ('app/schemas/legacy-home/br.com.expoworld.storage.ExplorationDatabase/'+str(v)+'.json')) for v in [4,5]]
for label, path in versions:
    schema=json.loads(path.read_text(encoding='utf-8'))['database']; version=schema['version']
    db=sqlite3.connect(':memory:')
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}',entity['tableName']))
        for index in entity.get('indices',[]): db.execute(index['createSql'].replace('${TABLE_NAME}',entity['tableName']))
    tables=table_names(db)
    db.execute("INSERT INTO cells(mode,y,x) VALUES ('real',2,3),('simulation',9,9)")
    has_date='firstDiscoveredAtMillis' in names(db,'cells')
    if has_date: db.execute("UPDATE cells SET firstDiscoveredAtMillis=17000 WHERE mode='real'")
    if 'route_sessions' in tables:
        db.execute("INSERT INTO route_sessions(mode,startedAtMillis,endedAtMillis,completed) VALUES ('real',1000,2000,1)")
        db.execute("INSERT INTO route_samples VALUES ('real',0,-25.43,-49.27,0,12,1,-1,0)")
        if 'newAreaSquareMeters' in names(db,'route_sessions'): db.execute("UPDATE route_sessions SET newAreaSquareMeters=123")
    if 'saved_routes' in tables: db.execute("INSERT INTO saved_routes(id,title,startedAtMillis) VALUES ('saved:old','Preservado',1000)")
    if 'trip_cells' in tables: db.execute("INSERT INTO trip_cells(mode,y,x) VALUES ('real',2,3)")
    if 'street_visits' in tables: db.execute("INSERT INTO street_visits VALUES ('real','walk','v1','street',0,'bairro',10)")
    for start in range(version,3):
        for statement in migrations[start]: db.execute(statement)
    normalize(db);normalize(db)  # Safe for either version-4/5 schema and idempotent.
    for entity in expected['entities']:
        actual=db.execute('PRAGMA table_info('+entity['tableName']+')').fetchall(); primary=entity['primaryKey']['columnNames']
        wanted={f['columnName']:(f['affinity'],int(f.get('notNull',False)), primary.index(f['columnName'])+1 if f['columnName'] in primary else 0, f.get('defaultValue')) for f in entity['fields']}
        found={r[1]:(r[2],r[3],r[5],r[4]) for r in actual}
        assert found==wanted,(label,entity['tableName'],found,wanted)
        for index in entity.get('indices',[]):
            assert [r[2] for r in db.execute('PRAGMA index_info('+index['name']+')')]==index['columnNames']
    assert db.execute("SELECT COUNT(*) FROM cells WHERE mode='real'").fetchone()[0] == (4 if version<3 else 1)
    if 'route_samples' in tables: assert db.execute('SELECT speedKmh FROM route_samples').fetchone()==(12.0,)
    if 'saved_routes' in tables: assert db.execute('SELECT title FROM saved_routes').fetchone()==('Preservado',)
    if 'trip_cells' in tables: assert db.execute('SELECT y,x FROM trip_cells').fetchone()==(2,3)
    if has_date: assert db.execute("SELECT firstDiscoveredAtMillis FROM cells WHERE mode='real'").fetchone()==(17000,)
    db.execute("INSERT OR IGNORE INTO street_visits VALUES ('real','walk','v1','street',0,'bairro',10)")
    db.execute("INSERT OR IGNORE INTO street_visits VALUES ('real','bike','v1','street',0,'bairro',10)")
    db.execute("INSERT INTO street_visits VALUES ('real','car','v1','street',1,'bairro',10)")
    db.execute("INSERT INTO street_visits VALUES ('simulation','car','v1','street',0,'bairro',999)")
    query=next(q for q in queries if q.startswith('SELECT neighborhoodId, SUM(meters)'))
    assert db.execute(query,{'mode':'real','dataset':'v1'}).fetchall()==[('bairro',20.0)]
    assert not db.execute(query,{'mode':'real','dataset':'v2'}).fetchall()
    before={table:db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0] for table in ['cells','route_sessions','route_samples','street_visits']}
    db.execute("INSERT INTO planned_routes VALUES ('plan','Volta',123,'{}')")
    assert {table:db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0] for table in before}==before
    assert db.execute("SELECT title FROM planned_routes WHERE id='plan'").fetchone()==('Volta',)
    db.close()
    print('PASS:',label,'-> 8: cells, trips, titles/dates/credits preserved; unified schema, repeat-safe normalizer and shared unique street totals.')
print('PASS: all nine historical schema variants from both branches; host SQLite (device tests still required).')
