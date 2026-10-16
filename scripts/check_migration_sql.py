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
new_schema = json.loads((schemas / "6.json").read_text(encoding="utf-8"))
source = (root / "app/src/main/java/br/com/expoworld/storage/ExplorationDatabase.kt").read_text(encoding="utf-8")
migration = re.findall(r'db\.execSQL\("([^"\n]+)"\)', source)
queries = re.findall(r'@Query\("([^"\n]+)"\)', source)
assert len(migration) == 10

def query(prefix):
    return next(q for q in queries if q.startswith(prefix))

folder = (root / ".tools/validation").resolve()
folder.mkdir(parents=True, exist_ok=True)
path = folder / ("route-migration-" + uuid.uuid4().hex + ".sqlite")
assert path.parent.resolve() == folder
db = sqlite3.connect(path)
db.execute(old_schema["database"]["entities"][0]["createSql"].replace("${TABLE_NAME}", "cells"))
db.execute("INSERT INTO cells(mode, y, x) VALUES ('real', 0, 0)")
db.execute("INSERT INTO cells(mode, y, x) VALUES ('real', -1, -1)")
db.execute("INSERT INTO cells(mode, y, x) VALUES ('simulation', -3, 5)")
db.commit()
with db:
    for statement in migration:
        if statement.startswith("ALTER TABLE route_sessions ADD COLUMN"):
            db.execute("INSERT INTO route_sessions VALUES ('legacy', 500, NULL, 0)")
            db.execute("INSERT INTO route_samples VALUES ('legacy', 0, 0, 0, 0, 10, 1, -1, 0)")
        if statement.startswith("ALTER TABLE trip_cells ADD COLUMN"):
            db.execute("INSERT INTO trip_cells VALUES ('legacy', 0, 0)")
        db.execute(statement)
assert db.execute("SELECT newAreaSquareMeters FROM route_sessions WHERE mode = 'legacy'").fetchone() == (None,)
assert db.execute("SELECT completed FROM route_sessions WHERE mode = 'legacy'").fetchone() == (0,)

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
assert db.execute("SELECT elapsedSeconds FROM trip_cells WHERE mode = 'legacy'").fetchone() == (-1,)
assert db.execute("SELECT COUNT(*) FROM cells WHERE firstDiscoveredAtMillis IS NOT NULL").fetchone() == (0,)
# The actual grouping query must keep negative coordinates in floor-based bins.
coverage_query = query("SELECT MIN(x) AS minX, MAX(x) AS maxX, MIN(y) AS minY, MAX(y) AS maxY FROM cells WHERE mode = :mode AND")
coverage = db.execute(coverage_query, {"mode": "real", "minY": -100, "maxY": 100, "minX": -100, "maxX": 100, "stride": 8}).fetchall()
assert set(coverage) == {(-2, -1, -2, -1), (0, 1, 0, 1)}
assert len(db.execute(query("SELECT * FROM cells WHERE mode = 'real'"), {"afterY": -2147483648, "afterX": -2147483648}).fetchall()) == 8

with db:
    db.execute("INSERT INTO route_sessions(mode, startedAtMillis, endedAtMillis, completed) VALUES ('real', 1000, NULL, 0)")
    db.execute("INSERT INTO route_samples VALUES ('real', 0, 0, 0, 0, 54, 1, -1, 0)")
    db.execute("INSERT INTO route_samples VALUES ('simulation', 0, 0, 0, 0, 0, 1, 0, 10)")
assert db.execute(query("SELECT * FROM route_sessions"), {"mode": "real"}).fetchone() is None
with db:
    db.execute(query("UPDATE route_sessions SET completed"), {"mode": "real", "endedAt": 2000})
db.close()

db = sqlite3.connect(path)
assert db.execute(query("SELECT * FROM route_sessions"), {"mode": "real"}).fetchone()[3] == 1
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
        db.execute("INSERT INTO cells(mode, y, x) VALUES ('real', 100, 100)")
        db.execute("INSERT INTO route_samples VALUES ('real', 1, 0, 0, 1, 0, 0, -1, 0)")
        raise RuntimeError("simulated storage failure")
except RuntimeError:
    pass
assert db.execute("SELECT COUNT(*) FROM cells WHERE mode = 'real'").fetchone()[0] == 8
assert db.execute(query("SELECT * FROM route_samples"), {"mode": "real"}).fetchall() == []
assert len(db.execute(query("SELECT * FROM route_samples"), {"mode": "simulation"}).fetchall()) == 1
db.close()
db = sqlite3.connect(path)
# A new trip counts only successful INSERT OR IGNORE rows, atomically with cells.
with db:
    db.execute("UPDATE route_sessions SET newAreaSquareMeters = 0 WHERE mode = 'real'")
    for x in (200, 200, 201):
        inserted = db.execute("INSERT OR IGNORE INTO cells(mode, y, x) VALUES ('real', 0, ?)", (x,)).rowcount
        db.execute(query("UPDATE route_sessions SET newAreaSquareMeters"), {"mode": "real", "area": inserted * 100.0})
assert db.execute("SELECT newAreaSquareMeters FROM route_sessions WHERE mode = 'real'").fetchone() == (200.0,)
try:
    with db:
        db.execute("INSERT INTO cells(mode, y, x) VALUES ('real', 0, 202)")
        db.execute(query("UPDATE route_sessions SET newAreaSquareMeters"), {"mode": "real", "area": 100.0})
        raise RuntimeError("simulated area transaction failure")
except RuntimeError:
    pass
assert db.execute("SELECT newAreaSquareMeters FROM route_sessions WHERE mode = 'real'").fetchone() == (200.0,)
assert db.execute("SELECT COUNT(*) FROM cells WHERE mode = 'real' AND x = 202").fetchone() == (0,)
with db:
    db.execute("UPDATE cells SET firstDiscoveredAtMillis = 1000 WHERE mode = 'real' AND y = 0 AND x = 200")
    db.execute("INSERT OR IGNORE INTO cells(mode, y, x, firstDiscoveredAtMillis) VALUES ('real', 0, 200, 2000)")
assert db.execute("SELECT firstDiscoveredAtMillis FROM cells WHERE mode = 'real' AND x = 200").fetchone() == (1000,)
db.close()
print("PASS: migration 1->2->3->4->5->6 matches Room schema; preserves cells/routes, unknown legacy dates/timing, original first discovery, negative coverage grouping, pagination, isolation, unique area and rollback (host SQLite).")
