package org.kavach.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Append-only event log (ADR-04). Rows are never updated except for the sync flag, and a re-received
 * event_id is ignored by the insert rule, so an accepted record cannot be silently rewritten.
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val eventId: String,
    val kind: String,
    val assetId: String,
    val bodyJson: String,      // canonical JSON of the signed body
    val signature: String,
    val keyId: String,
    val receivedAt: String,
    val synced: Boolean,       // true once the hub or a peer has acknowledged it
)

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE eventId = :id LIMIT 1")
    suspend fun byId(id: String): EventEntity?

    @Query("SELECT * FROM events ORDER BY receivedAt, eventId")
    suspend fun all(): List<EventEntity>

    @Query("SELECT * FROM events WHERE synced = 0 ORDER BY receivedAt, eventId")
    suspend fun unsynced(): List<EventEntity>

    @Query("SELECT eventId FROM events ORDER BY eventId")
    suspend fun ids(): List<String>

    @Query("UPDATE events SET synced = 1 WHERE eventId IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}

@Database(entities = [EventEntity::class], version = 1, exportSchema = false)
abstract class KavachDb : RoomDatabase() {
    abstract fun events(): EventDao
}
