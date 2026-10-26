package dev.walkcode.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Host::class, Project::class, AgentPreset::class, Workspace::class, Session::class, VoiceUsage::class],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class WalkcodeDatabase : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun projects(): ProjectDao
    abstract fun agentPresets(): AgentPresetDao
    abstract fun workspaces(): WorkspaceDao
    abstract fun sessions(): SessionDao
    abstract fun voiceUsage(): VoiceUsageDao

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

        /** Existing rows all get 0, so they keep their old order until the user drags one. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workspaces ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_usage` (`day` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                        "`calls` INTEGER NOT NULL, `textIn` INTEGER NOT NULL, `audioIn` INTEGER NOT NULL, " +
                        "`cachedIn` INTEGER NOT NULL, `textOut` INTEGER NOT NULL, `audioOut` INTEGER NOT NULL, " +
                        "`costUsd` REAL NOT NULL, PRIMARY KEY(`day`, `model`))",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN lastAddress TEXT")
            }
        }

        @Volatile private var instance: WalkcodeDatabase? = null

        fun get(context: Context): WalkcodeDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): WalkcodeDatabase =
            Room.databaseBuilder(context, WalkcodeDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
