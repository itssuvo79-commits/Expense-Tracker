package com.example.data

import kotlinx.coroutines.flow.Flow

class AppRepository(private val appDao: AppDao) {
    suspend fun insertUser(user: User) = appDao.insertUser(user)
    
    suspend fun getUserByEmail(email: String): User? = appDao.getUserByEmail(email)

    suspend fun insertTransaction(transaction: Transaction) = appDao.insertTransaction(transaction)

    suspend fun deleteTransaction(transaction: Transaction) = appDao.deleteTransaction(transaction)

    suspend fun deleteTransactionById(id: Long) = appDao.deleteTransactionById(id)

    fun getTransactions(userEmail: String): Flow<List<Transaction>> = appDao.getTransactionsForUser(userEmail)
}
