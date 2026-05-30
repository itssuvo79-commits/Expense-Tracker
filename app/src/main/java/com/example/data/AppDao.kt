package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // User functions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): User?

    // Transaction functions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)

    @Query("SELECT * FROM transactions WHERE userEmail = :userEmail ORDER BY timestamp DESC")
    fun getTransactionsForUser(userEmail: String): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE userEmail = :userEmail AND type = :type ORDER BY timestamp DESC")
    fun getTransactionsForUserByType(userEmail: String, type: String): Flow<List<Transaction>>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)
}
