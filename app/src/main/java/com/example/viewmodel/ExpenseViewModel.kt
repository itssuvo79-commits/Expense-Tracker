package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.data.Transaction
import com.example.data.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ExpenseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: AppRepository

    init {
        val database = AppDatabase.getDatabase(application)
        repository = AppRepository(database.appDao())
    }

    // Auth States
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _registerError = MutableStateFlow<String?>(null)
    val registerError: StateFlow<String?> = _registerError.asStateFlow()

    // Currency Setting
    private val _selectedCurrency = MutableStateFlow("$")
    val selectedCurrency: StateFlow<String> = _selectedCurrency.asStateFlow()

    // Filter States
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _categoryFilter = MutableStateFlow("All")
    val categoryFilter: StateFlow<String> = _categoryFilter.asStateFlow()

    private val _typeFilter = MutableStateFlow("All") // "All", "Income", "Expense"
    val typeFilter: StateFlow<String> = _typeFilter.asStateFlow()

    // Real-Time Transactions Streams
    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<Transaction>> = _currentUser
        .flatMapLatest { user ->
            if (user == null) {
                flowOf(emptyList())
            } else {
                repository.getTransactions(user.email)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Filtered Transactions
    val filteredTransactions: StateFlow<List<Transaction>> = combine(
        transactions,
        _searchQuery,
        _categoryFilter,
        _typeFilter
    ) { list, query, category, type ->
        list.filter { tx ->
            val matchesQuery = tx.title.contains(query, ignoreCase = true) ||
                    tx.category.contains(query, ignoreCase = true)
            val matchesCategory = category == "All" || tx.category.equals(category, ignoreCase = true)
            val matchesType = type == "All" || tx.type.equals(type, ignoreCase = true)
            matchesQuery && matchesCategory && matchesType
        }
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Totals Calculations
    val totalIncome: StateFlow<Double> = transactions
        .map { list -> list.filter { it.type == "income" }.sumOf { it.amount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalExpense: StateFlow<Double> = transactions
        .map { list -> list.filter { it.type == "expense" }.sumOf { it.amount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalBalance: StateFlow<Double> = combine(totalIncome, totalExpense) { inc, exp ->
        inc - exp
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Auth Actions
    fun login(email: String, passwordRaw: String) {
        viewModelScope.launch {
            _loginError.value = null
            if (email.isBlank() || passwordRaw.isBlank()) {
                _loginError.value = "Email and Password cannot be empty."
                return@launch
            }
            val user = repository.getUserByEmail(email.trim().lowercase())
            if (user == null) {
                _loginError.value = "User not found. Please register first."
            } else if (user.passwordHash != passwordRaw) {
                _loginError.value = "Invalid password."
            } else {
                _currentUser.value = user
            }
        }
    }

    fun register(name: String, email: String, passwordRaw: String) {
        viewModelScope.launch {
            _registerError.value = null
            if (name.isBlank() || email.isBlank() || passwordRaw.isBlank()) {
                _registerError.value = "All fields are required."
                return@launch
            }
            if (!email.contains("@")) {
                _registerError.value = "Please enter a valid email address."
                return@launch
            }
            val checkEmail = email.trim().lowercase()
            val existing = repository.getUserByEmail(checkEmail)
            if (existing != null) {
                _registerError.value = "An account with this email already exists."
            } else {
                val newUser = User(
                    email = checkEmail,
                    passwordHash = passwordRaw,
                    displayName = name.trim()
                )
                repository.insertUser(newUser)
                _currentUser.value = newUser
            }
        }
    }

    fun logout() {
        _currentUser.value = null
        _searchQuery.value = ""
        _categoryFilter.value = "All"
        _typeFilter.value = "All"
    }

    // Settings Configuration
    fun setCurrency(symbol: String) {
        _selectedCurrency.value = symbol
    }

    // Filter Updates
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateCategoryFilter(category: String) {
        _categoryFilter.value = category
    }

    fun updateTypeFilter(type: String) {
        _typeFilter.value = type
    }

    // Transaction Actions
    fun addTransaction(title: String, amountText: String, type: String, category: String) {
        val user = _currentUser.value ?: return
        val amount = amountText.toDoubleOrNull() ?: 0.0
        if (title.isBlank()) return
        if (amount <= 0.0) return

        viewModelScope.launch {
            val newTx = Transaction(
                userEmail = user.email,
                title = title.trim(),
                amount = amount,
                type = type.lowercase(),
                category = category,
                timestamp = System.currentTimeMillis()
            )
            repository.insertTransaction(newTx)
        }
    }

    fun deleteTransaction(tx: Transaction) {
        viewModelScope.launch {
            repository.deleteTransaction(tx)
        }
    }

    // Export Data (as formatted CSV via Android Action Share sheet)
    fun exportToCSV(context: Context): Boolean {
        val user = _currentUser.value ?: return false
        val txList = transactions.value
        if (txList.isEmpty()) return false

        try {
            val sDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val csvHeader = "ID,Title,Amount,Type,Category,Date\n"
            val csvContent = StringBuilder(csvHeader)
            
            for (tx in txList) {
                val formattedDate = sDateFormat.format(Date(tx.timestamp))
                // Clean CSV values of commas
                val title = tx.title.replace(",", " ")
                val category = tx.category.replace(",", " ")
                csvContent.append("${tx.id},$title,${tx.amount},${tx.type},$category,$formattedDate\n")
            }

            // Write to local cache file
            val exportDir = File(context.cacheDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()
            
            val exportFile = File(exportDir, "ExpenTrack_Report_${user.displayName.replace(" ", "_")}.csv")
            exportFile.writeText(csvContent.toString())

            // Create share intent
            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, exportFile)
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "ExpenTrack Export: ${user.displayName}'s Financial Report")
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooserIntent = Intent.createChooser(shareIntent, "Export Financial Report CSV").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // Export text summaries for direct sharing
    fun shareTextSummary(context: Context) {
        val user = _currentUser.value ?: return
        val symbol = _selectedCurrency.value
        val bal = totalBalance.value
        val inc = totalIncome.value
        val exp = totalExpense.value
        
        val summary = """
            📊 ExpenTrack Pro - Financial Report
            Owner: ${user.displayName}
            Date: ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}
            ------------------------------------
            💰 Total Balance: $symbol${String.format("%.2f", bal)}
            📈 Total Income:  $symbol${String.format("%.2f", inc)}
            📉 Total Expenses: $symbol${String.format("%.2f", exp)}
            ------------------------------------
            Tracking smartly with ExpenTrack Pro!
        """.trimIndent()
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "My ExpenTrack Summary")
            putExtra(Intent.EXTRA_TEXT, summary)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserIntent = Intent.createChooser(shareIntent, "Share Summary via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooserIntent)
    }
}
