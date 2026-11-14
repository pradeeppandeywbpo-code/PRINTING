package com.example.data.local

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
import com.example.domain.model.ConnectionType
import com.example.domain.model.DiscoveredPrinter
import com.example.domain.model.PrinterProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "saved_printers")
data class SavedPrinterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val connectionType: String,
    val address: String,
    val port: Int,
    val protocol: String,
    val details: String,
    val savedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun toDomain(): DiscoveredPrinter = DiscoveredPrinter(
        id = id,
        name = name,
        connectionType = ConnectionType.entries.firstOrNull { it.name == connectionType }
            ?: ConnectionType.WIFI_RAW,
        address = address,
        port = port,
        defaultProtocol = PrinterProtocol.entries.firstOrNull { it.name == protocol }
            ?: PrinterProtocol.PCL_RASTER,
        hasPermission = true,
        details = details
    )
}

@Entity(tableName = "print_jobs")
data class PrintJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentTitle: String,
    val printerName: String,
    val connectionType: String,
    val protocolUsed: String,
    val paperSize: String,
    val pageCount: Int,
    val copies: Int,
    val dpi: Int,
    val bytesTransferred: Long,
    val status: String,
    val errorMessage: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

@Dao
interface PrintDao {
    @Query("SELECT * FROM saved_printers ORDER BY savedAtEpochMs DESC")
    fun observeSavedPrinters(): Flow<List<SavedPrinterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedPrinter(printer: SavedPrinterEntity)

    @Query("DELETE FROM saved_printers WHERE id = :id")
    suspend fun deleteSavedPrinter(id: String)

    @Query("SELECT * FROM print_jobs ORDER BY timestampMs DESC LIMIT 50")
    fun observePrintJobs(): Flow<List<PrintJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrintJob(job: PrintJobEntity)

    @Query("DELETE FROM print_jobs")
    suspend fun clearPrintJobs()
}

@Database(
    entities = [SavedPrinterEntity::class, PrintJobEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PrintDatabase : RoomDatabase() {
    abstract fun printDao(): PrintDao

    companion object {
        @Volatile
        private var INSTANCE: PrintDatabase? = null

        fun getInstance(context: Context): PrintDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PrintDatabase::class.java,
                    "printforge_local.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class PrintHistoryRepository(private val dao: PrintDao) {
    val savedPrinters: Flow<List<DiscoveredPrinter>> =
        dao.observeSavedPrinters().map { list -> list.map { it.toDomain() } }

    val printJobs: Flow<List<PrintJobEntity>> = dao.observePrintJobs()

    suspend fun savePrinter(printer: DiscoveredPrinter) {
        dao.upsertSavedPrinter(
            SavedPrinterEntity(
                id = printer.id,
                name = printer.name,
                connectionType = printer.connectionType.name,
                address = printer.address,
                port = printer.port,
                protocol = printer.defaultProtocol.name,
                details = printer.details
            )
        )
    }

    suspend fun removeSavedPrinter(id: String) {
        dao.deleteSavedPrinter(id)
    }

    suspend fun recordJob(job: PrintJobEntity) {
        dao.insertPrintJob(job)
    }

    suspend fun clearJobs() {
        dao.clearPrintJobs()
    }
}
