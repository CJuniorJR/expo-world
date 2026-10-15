"""Host SQLite validation of the actual migration SQL and Room-exported schema.

This does not replace Android/Room instrumentation tests. No GPS data or API keys
are read. Generated validation databases stay under .tools/validation (gitignored).
"""
import json
import re
import sqlite3
import uuid
from pathlib import Path

root = Path(__file__).resolve().parent.parent
schemas = root / "app/schemas/br.com.expoworld.storage.ExplorationDatabase"
old_schema = json.loads((schemas / "1.json").read_text(encoding="utf-8"))
new_schema = json.loads((schemas / "5.json").read_text(encoding="utf-8"))
source = (root / "app/src/main/java/br/com/expoworld/storage/ExplorationDatabase.kt").read_text(encoding="utf-8")
blocks = re.findall(r'val MIGRATION_(\d+)_(\d+) = object.*?override fun migrate.*?\{(.*?)\n    }\n}', source, re.S)
migrations = {int(start): re.findall(r'db\.execSQL\("([^"\n]+)"\)', body) for start, end, body in blocks}
migration = [sql for start in sorted(migrations) for sql in migrations[start]]
queries = re.findall(r'@Query\("([^"\n]+)"\)', source)
assert sorted(migrations) == [1, 2, 3, 4]

def query(prefix):
    return next(q for q in queries if q.startswith(prefix))

folder = (root / ".tools/validation").resolve()
folder.mkdir(parents=True, exist_ok=True)
path = folder / ("route-migration-" + uuid.uuid4().hex + ".sqlite")
assert path.parent.resolve() == folder
db = sqlite3.connect(path)
db.execute(old_schema["database"]["entities"][0]["createSql"].replace("${TABLE_NAME}", "cells"))
db.execute("INSERT INTO cells VALUES ('real', 0, 0)")
db.execute("INSERT INTO cells VALUES ('real', -1, -1)")
db.execute("INSERT INTO cells VALUES ('simulation', -3, 5)")
db.commit()
with db:
    for statement in [sql for start in [1, 2, 3] for sql in migrations[start]]:
        db.execute(statement)
    db.execute("INSERT INTO route_sessions VALUES ('legacy', 10, 20, 1)")
    db.execute("INSERT INTO saved_routes VALUES ('legacy', 'Preservado', 10)")
    for statement in migrations[4]:
        db.execute(statement)
assert db.execute("SELECT activity FROM route_sessions WHERE mode='legacy'").fetchone() == ('car',)
assert db.execute("SELECT title, activity FROM saved_routes WHERE id='legacy'").fetchone() == ('Preservado', 'car')

# Compare migration results to the compiler-exported Room schema, including PK
# ordering and nullability. This catches destructive or incompatible SQL changes.
for entity in new_schema["database"]["entities"]:
    actual = db.execute("PRAGMA table_info(" + entity["tableName"] + ")").fetchall()
    primary = entity["primaryKey"]["columnNames"]
    expected = [(f["columnName"], f["affinity"], int(f.get("notNull", False)),
                 primary.index(f["columnName"]) + 1 if f["columnName"] in primary else 0)
                for f in entity["fields"]]
    assert [(r[1], r[2], r[3], r[5]) for r in actual] == expected
expected_cells = {(0, 0), (0, 1), (1, 0), (1, 1), (-2, -2), (-2, -1), (-1, -2), (-1, -1)}
assert set(db.execute("SELECT y, x FROM cells WHERE mode = 'real'").fetchall()) == expected_cells
assert set(db.execute("SELECT y, x FROM cells WHERE mode = 'simulation'").fetchall()) == {(-6, 10), (-6, 11), (-5, 10), (-5, 11)}

with db:
    db.execute("INSERT INTO route_sessions(mode, startedAtMillis, endedAtMillis, completed) VALUES ('real', 1000, NULL, 0)")
    db.execute("INSERT INTO route_samples VALUES ('real', 0, 0, 0, 0, 54, 1, -1, 0)")
    db.execute("INSERT INTO route_samples VALUES ('simulation', 0, 0, 0, 0, 0, 1, 0, 10)")
assert db.execute(query("SELECT * FROM route_sessions WHERE mode = :mode AND"), {"mode": "real"}).fetchone() is None
with db:
    db.execute(query("UPDATE route_sessions"), {"mode": "real", "endedAt": 2000})
db.close()

db = sqlite3.connect(path)
assert db.execute(query("SELECT * FROM route_sessions WHERE mode = :mode AND"), {"mode": "real"}).fetchone()[3] == 1
assert db.execute(query("SELECT * FROM route_samples"), {"mode": "real"}).fetchone()[5] == 54
with db:
    db.execute("INSERT INTO route_samples VALUES ('real', 20001, 0, 0, 100, 0, 0, 0, 7)")
    db.execute(query("DELETE FROM route_samples WHERE mode = :mode AND"), {"mode": "real", "minSequence": 2})
assert db.execute(query("SELECT * FROM route_samples"), {"mode": "real"}).fetchone()[1] == 20001
assert len(db.execute(query("SELECT * FROM route_samples"), {"mode": "simulation"}).fetchall()) == 1

with db:
    db.execute(query("DELETE FROM route_samples WHERE mode = :mode"), {"mode": "real"})
    # Simulate a failed atomic save: the exploration INSERT and route sample must
    # both roll back when a subsequent operation fails.
try:
    with db:
        db.execute("INSERT INTO cells VALUES ('real', 100, 100)")
        db.execute("INSERT INTO route_samples VALUES ('real', 1, 0, 0, 1, 0, 0, -1, 0)")
        raise RuntimeError("simulated storage failure")
except RuntimeError:
    pass
assert db.execute("SELECT COUNT(*) FROM cells WHERE mode = 'real'").fetchone()[0] == 8
assert db.execute(query("SELECT * FROM route_samples"), {"mode": "real"}).fetchall() == []
assert len(db.execute(query("SELECT * FROM route_samples"), {"mode": "simulation"}).fetchall()) == 1
with db:
    for mode, activity in [('real','walk'), ('real','walk'), ('real','bike'), ('simulation','walk')]:
        db.execute("INSERT OR IGNORE INTO street_visits VALUES (?, ?, 'v1', 'street', 0, 'bairro', 10.0)", (mode, activity))
assert db.execute("SELECT COUNT(*) FROM street_visits").fetchone()[0] == 3
assert db.execute(query("SELECT neighborhoodId, SUM(meters)"), {'mode':'real','activity':'walk','dataset':'v1'}).fetchone() == ('bairro', 10.0)
assert db.execute(query("SELECT neighborhoodId, SUM(meters)"), {'mode':'real','activity':'walk','dataset':'v2'}).fetchall() == []
with db:
    db.execute(query("DELETE FROM street_visits WHERE mode = 'simulation'"))
assert db.execute("SELECT COUNT(*) FROM street_visits").fetchone()[0] == 2
db.close()
print("PASS: migrations 1->2->3->4->5 match Room schema; old cells/trips/titles preserved, activity defaults to car, street visits deduplicate and isolate modes/activities/versions; retention, reopen and rollback verified (host SQLite).")
