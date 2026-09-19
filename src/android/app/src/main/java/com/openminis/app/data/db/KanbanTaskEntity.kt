package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "kanban_tasks")
data class KanbanTaskEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    /** "todo" / "doing" / "done". */
    val status: String = "todo",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface KanbanTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: KanbanTaskEntity)

    @Query("SELECT * FROM kanban_tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<KanbanTaskEntity>>

    @Query("UPDATE kanban_tasks SET status=:status, updatedAt=:updatedAt WHERE id=:id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)

    @Delete
    suspend fun delete(task: KanbanTaskEntity)
}
