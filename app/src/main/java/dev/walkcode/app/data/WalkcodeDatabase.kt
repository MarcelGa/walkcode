package dev.walkcode.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Host::class, Project::class, AgentPreset::class, Workspace::class, Session::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class WalkcodeDatabase : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun projects(): ProjectDao
    abstract fun agentPresets(): AgentPresetDao
    abstract fun workspaces(): WorkspaceDao
    abstract fun sessions(): SessionDao

    companion object {
        private const val NAME = "walkcode.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN label TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN autoTitle TEXT")
            }
        }

        @Volatile private var instance: WalkcodeDatabase? = null

        fun get(context: Context): WalkcodeDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): WalkcodeDatabase =
            Room.databaseBuilder(context, WalkcodeDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(SeedBuiltInAgents)
                .build()

        /** Built-in agent presets are inserted with raw SQL so no coroutine is needed at create time. */
        private object SeedBuiltInAgents : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                for (p in BuiltInAgents.defaults()) {
                    db.execSQL(
                        "INSERT INTO agent_presets (id, name, command, resumeCommand, builtIn, sortOrder) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(p.id, p.name, p.command, p.resumeCommand, if (p.builtIn) 1 else 0, p.sortOrder),
                    )
                }
            }
        }
    }
}
