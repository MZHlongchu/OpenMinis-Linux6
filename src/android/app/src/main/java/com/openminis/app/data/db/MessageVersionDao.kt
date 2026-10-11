package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** [T-msg-version-archive] DAO for archived message versions. */
@Dao
interface MessageVersionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(versions: List<MessageVersionEntity>)

    /** Highest archived version index for a message; 0 when never archived. */
    @Query("SELECT COALESCE(MAX(version_index), 0) FROM message_versions WHERE message_id = :messageId")
    suspend fun maxVersionIndex(messageId: String): Int

    @Query("SELECT * FROM message_versions WHERE message_id = :messageId ORDER BY version_index DESC")
    suspend fun versionsFor(messageId: String): List<MessageVersionEntity>

    @Query("SELECT COUNT(*) FROM message_versions WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    /** Messages in this session that have at least one archived version. */
    @Query("SELECT DISTINCT message_id FROM message_versions WHERE session_id = :sessionId")
    suspend fun messageIdsWithVersions(sessionId: String): List<String>

    @Query("DELETE FROM message_versions WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    /**
     * [T-bodies-gc] 归档版本同样引用正文文件（见 MessageVersionEntity 的 bodyRef
     * 说明）：回收对账必须把版本表算进去，否则删掉一个会话会把另一处仍被归档
     * 引用的正文文件删掉。
     */
    @Query("SELECT DISTINCT body_ref FROM message_versions WHERE session_id = :sessionId AND body_ref IS NOT NULL")
    suspend fun bodyRefsForSession(sessionId: String): List<String>

    /** [T-bodies-gc] 全库仍被归档版本引用的正文 ref（回收对账用）。 */
    @Query("SELECT DISTINCT body_ref FROM message_versions WHERE body_ref IS NOT NULL")
    suspend fun allBodyRefs(): List<String>
}
