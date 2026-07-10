package com.example.database

import kotlinx.coroutines.flow.Flow

class TermRepository(private val database: TermDatabase) {
    val recentHistory: Flow<List<HistoryEntity>> = database.historyDao().getRecentHistory()
    val bookmarks: Flow<List<BookmarkEntity>> = database.bookmarkDao().getAllBookmarks()

    suspend fun insertHistory(command: String) {
        if (command.isNotBlank()) {
            database.historyDao().insertHistory(HistoryEntity(command = command))
        }
    }

    suspend fun clearHistory() {
        database.historyDao().clearHistory()
    }

    suspend fun addBookmark(name: String, path: String) {
        database.bookmarkDao().insertBookmark(BookmarkEntity(name = name, path = path))
    }

    suspend fun removeBookmark(id: Int) {
        database.bookmarkDao().deleteBookmark(id)
    }

    fun isBookmarked(path: String): Flow<Boolean> {
        return database.bookmarkDao().isBookmarked(path)
    }
}
