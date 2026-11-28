package org.kavach.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Index
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

/** Server-issued mission cache; mutable projection is separate from the signed append-only log. */
@Entity(tableName = "assigned_tasks", indices = [Index("squadId"), Index("state")])
data class AssignedTaskEntity(
    @PrimaryKey val missionId: String,
    val crossingId: String,
    val squadId: String,
    val priority: String,
    val state: String,
    val warningId: String?,
    val createdAt: String,
    val updatedAt: String,
)

/** Durable operation outbox. A successful hub response means persisted by the hub, not read by a person. */
@Entity(tableName = "task_outbox", indices = [Index("missionId"), Index("synced")])
data class TaskOutboxEntity(
    @PrimaryKey val idempotencyKey: String,
    val missionId: String,
    val operation: String,
    val payloadJson: String,
    val queuedAt: String,
    val synced: Boolean = false,
    val lastError: String? = null,
)

@Dao
interface TaskDao {
    @Upsert
    suspend fun upsertTasks(tasks: List<AssignedTaskEntity>)

    @Query("SELECT * FROM assigned_tasks ORDER BY updatedAt DESC, missionId")
    suspend fun tasks(): List<AssignedTaskEntity>

    @Query("SELECT * FROM assigned_tasks WHERE missionId = :id LIMIT 1")
    suspend fun task(id: String): AssignedTaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(action: TaskOutboxEntity): Long

    @Query("SELECT * FROM task_outbox WHERE synced = 0 ORDER BY queuedAt, CASE operation WHEN 'transition:IN_PROGRESS' THEN 0 WHEN 'observation' THEN 1 ELSE 2 END, idempotencyKey")
    suspend fun pending(): List<TaskOutboxEntity>

    @Query("SELECT * FROM task_outbox ORDER BY queuedAt, idempotencyKey")
    suspend fun allActions(): List<TaskOutboxEntity>

    @Query("SELECT * FROM task_outbox WHERE missionId = :missionId ORDER BY queuedAt, idempotencyKey")
    suspend fun actions(missionId: String): List<TaskOutboxEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM task_outbox WHERE missionId = :missionId AND operation = :operation)")
    suspend fun hasAction(missionId: String, operation: String): Boolean

    @Query("UPDATE task_outbox SET synced = 1, lastError = NULL WHERE idempotencyKey = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE task_outbox SET lastError = :error WHERE idempotencyKey = :id")
    suspend fun markError(id: String, error: String)
}

@Database(entities = [EventEntity::class, AssignedTaskEntity::class, TaskOutboxEntity::class], version = 2, exportSchema = false)
abstract class KavachDb : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun tasks(): TaskDao
}
