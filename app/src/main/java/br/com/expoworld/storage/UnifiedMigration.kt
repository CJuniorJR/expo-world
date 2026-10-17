package br.com.expoworld.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Versions 4/5 were released with different schemas on two branches. Inspect columns
 * instead of assuming which branch created the database; never drop personal data. */
private fun unifySchema(db: SupportSQLiteDatabase) {
    fun ensureColumn(table: String, name: String, definition: String) {
        val exists = db.query("PRAGMA table_info($table)").use { cursor ->
            val index = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(index) == name) found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $name $definition")
    }
    db.execSQL("CREATE TABLE IF NOT EXISTS saved_routes (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, startedAtMillis INTEGER NOT NULL, activity TEXT NOT NULL DEFAULT 'car')")
    db.execSQL("CREATE TABLE IF NOT EXISTS street_visits (mode TEXT NOT NULL, activity TEXT NOT NULL, dataset TEXT NOT NULL, streetId TEXT NOT NULL, bin INTEGER NOT NULL, neighborhoodId TEXT NOT NULL, meters REAL NOT NULL, PRIMARY KEY(mode, activity, dataset, streetId, bin))")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_street_visits_mode_activity_dataset_neighborhoodId ON street_visits(mode, activity, dataset, neighborhoodId)")
    db.execSQL("CREATE TABLE IF NOT EXISTS trip_cells (mode TEXT NOT NULL, y INTEGER NOT NULL, x INTEGER NOT NULL, elapsedSeconds REAL NOT NULL DEFAULT -1, PRIMARY KEY(mode, y, x))")
    ensureColumn("route_sessions", "activity", "TEXT NOT NULL DEFAULT 'car'")
    ensureColumn("route_sessions", "newAreaSquareMeters", "REAL DEFAULT NULL")
    ensureColumn("saved_routes", "activity", "TEXT NOT NULL DEFAULT 'car'")
    ensureColumn("cells", "firstDiscoveredAtMillis", "INTEGER DEFAULT NULL")
    ensureColumn("trip_cells", "elapsedSeconds", "REAL NOT NULL DEFAULT -1")
}
private fun unifiedMigration(start: Int) = object : Migration(start, 7) {
    override fun migrate(db: SupportSQLiteDatabase) = unifySchema(db)
}
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS planned_routes (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, payload TEXT NOT NULL)")
    }
}
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE route_samples ADD COLUMN accuracyMeters REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE route_samples ADD COLUMN accepted INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE route_samples ADD COLUMN rawSpeedMetersSecond REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE route_samples ADD COLUMN monotonicSeconds REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE route_samples ADD COLUMN streetIds TEXT DEFAULT NULL")
    }
}
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3,
    unifiedMigration(3), unifiedMigration(4), unifiedMigration(5), unifiedMigration(6), MIGRATION_7_8, MIGRATION_8_9)
