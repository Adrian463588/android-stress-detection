package com.stressdetector.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "search_history",
    primaryKeys = ["canonicalItemId", "category"]
)
data class SearchHistoryEntity(val canonicalItemId: String, val canonicalTitle: String, val category: String, val lastSearchedAt: Long)

@Dao
interface SearchHistoryDao {
    @Query(
        "SELECT * FROM search_history WHERE category = :category " +
            "ORDER BY lastSearchedAt DESC LIMIT :limit"
    )
    fun observeRecent(category: String, limit: Int = 5): Flow<List<SearchHistoryEntity>>

    @Query(
        "SELECT * FROM search_history WHERE category = :category " +
            "ORDER BY lastSearchedAt DESC LIMIT :limit"
    )
    suspend fun getRecent(category: String, limit: Int = 5): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE canonicalItemId = :canonicalItemId AND category = :category")
    suspend fun delete(canonicalItemId: String, category: String)

    @Query("DELETE FROM search_history WHERE category = :category")
    suspend fun clearCategory(category: String)
}
