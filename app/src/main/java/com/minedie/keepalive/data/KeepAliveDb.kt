package com.minedie.keepalive.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val detail: String,
    val packageName: String?,
    val createdAt: Long,
)

@Entity(tableName = "snapshot")
data class SnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val phase: String,
    val daemonPid: Int,
    val reportedAt: Long,
    val runningPackages: String,
    val pullsLastHour: Int,
    val hookError: String?,
    val gaveUpPackages: String,
    val runningServices: String = "",
)

@Dao
interface EventDao {
    @Insert
    suspend fun insert(items: List<EventEntity>)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    @Query("DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY id ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM events")
    suspend fun clear()

    @Query("SELECT * FROM events ORDER BY id DESC")
    suspend fun all(): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<EventEntity>

    @Transaction
    suspend fun insertAndTrim(items: List<EventEntity>, retention: Int) {
        if (items.isNotEmpty()) insert(items)
        val extra = count() - retention
        if (extra > 0) deleteOldest(extra)
    }
}

@Dao
interface SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SnapshotEntity)

    @Query("SELECT * FROM snapshot WHERE id = 1")
    suspend fun get(): SnapshotEntity?
}

@Database(entities = [EventEntity::class, SnapshotEntity::class], version = 3, exportSchema = false)
abstract class KeepAliveDb : RoomDatabase() {
    abstract fun events(): EventDao

    abstract fun snapshot(): SnapshotDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE snapshot ADD COLUMN gaveUpPackages TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE snapshot ADD COLUMN runningServices TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile private var instance: KeepAliveDb? = null

        fun get(context: Context): KeepAliveDb {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KeepAliveDb::class.java,
                    "keepalive.db",
                ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
