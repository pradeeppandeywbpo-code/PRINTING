package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.local.PrintJobEntity
import com.example.domain.model.ConnectionType
import com.example.domain.model.DiscoveredPrinter
import com.example.domain.model.PrinterProtocol
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

@IgnoreExtraProperties
data class CloudSavedPrinterDto(
    @DocumentId val id: String = "",
    val userId: String = "",
    val printerKey: String = "",
    val name: String = "",
    val connectionType: String = ConnectionType.WIFI_RAW.name,
    val address: String = "",
    val port: Long = 9100L,
    val protocol: String = PrinterProtocol.PCL_RASTER.name,
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomain(): DiscoveredPrinter = DiscoveredPrinter(
        id = printerKey.ifBlank { id },
        name = name,
        connectionType = ConnectionType.entries.firstOrNull { it.name == connectionType }
            ?: ConnectionType.WIFI_RAW,
        address = address,
        port = port.toInt(),
        defaultProtocol = PrinterProtocol.entries.firstOrNull { it.name == protocol }
            ?: PrinterProtocol.PCL_RASTER,
        hasPermission = true,
        details = details
    )
}

@IgnoreExtraProperties
data class CloudPrintJobDto(
    @DocumentId val id: String = "",
    val userId: String = "",
    val documentTitle: String = "",
    val printerName: String = "",
    val connectionType: String = "",
    val protocolUsed: String = "",
    val paperSize: String = "",
    val pageCount: Long = 1L,
    val copies: Long = 1L,
    val dpi: Long = 300L,
    val bytesTransferred: Long = 0L,
    val status: String = "Completed",
    val errorMessage: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toEntity(): PrintJobEntity = PrintJobEntity(
        id = id.hashCode().toLong(),
        documentTitle = documentTitle,
        printerName = printerName,
        connectionType = connectionType,
        protocolUsed = protocolUsed,
        paperSize = paperSize,
        pageCount = pageCount.toInt(),
        copies = copies.toInt(),
        dpi = dpi.toInt(),
        bytesTransferred = bytesTransferred,
        status = status,
        errorMessage = errorMessage,
        timestampMs = createdAt?.toDate()?.time ?: System.currentTimeMillis()
    )
}

class CloudPrintRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun sanitizeDocumentId(rawKey: String): String {
        val sanitized = rawKey.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .trim('_')
            .take(120)
        return sanitized.ifEmpty { "printer_${UUID.randomUUID().toString().replace("-", "")}" }
    }

    suspend fun ensureUserProfile(displayName: String, email: String?): Result<Unit> {
        val uid = requireUserId()
        val userDoc = db.collection("users").document(uid)
        return try {
            val snap = userDoc.get().await()
            val safeName = displayName.trim().ifEmpty { "PrintForge User" }.take(100)
            if (snap.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "displayName" to safeName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!email.isNullOrBlank()) {
                    updates["email"] = email.take(200)
                }
                userDoc.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "displayName" to safeName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!email.isNullOrBlank()) {
                    payload["email"] = email.take(200)
                }
                userDoc.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userDoc.path)
            Result.failure(e)
        }
    }

    fun observeSavedPrinters(userId: String): Flow<List<DiscoveredPrinter>> {
        val colRef = db.collection("users").document(userId).collection("saved_printers")
        return colRef
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    CloudSavedPrinterDto::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).map { it.toDomain() }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, colRef.path)
                }
                throw error
            }
    }

    suspend fun savePrinter(printer: DiscoveredPrinter): Result<String> {
        val uid = requireUserId()
        val docId = sanitizeDocumentId(printer.id)
        val docRef = db.collection("users").document(uid).collection("saved_printers").document(docId)
        return try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mapOf(
                    "printerKey" to printer.id.take(200),
                    "name" to printer.name.ifBlank { "Printer" }.take(120),
                    "connectionType" to printer.connectionType.name,
                    "address" to printer.address.ifBlank { "unknown" }.take(200),
                    "port" to printer.port.coerceIn(0, 65535),
                    "protocol" to printer.defaultProtocol.name,
                    "details" to printer.details.take(300),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "printerKey" to printer.id.take(200),
                    "name" to printer.name.ifBlank { "Printer" }.take(120),
                    "connectionType" to printer.connectionType.name,
                    "address" to printer.address.ifBlank { "unknown" }.take(200),
                    "port" to printer.port.coerceIn(0, 65535),
                    "protocol" to printer.defaultProtocol.name,
                    "details" to printer.details.take(300),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deletePrinter(rawPrinterId: String): Result<Unit> {
        val uid = requireUserId()
        val docId = sanitizeDocumentId(rawPrinterId)
        val docRef = db.collection("users").document(uid).collection("saved_printers").document(docId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observePrintJobs(userId: String): Flow<List<PrintJobEntity>> {
        val colRef = db.collection("users").document(userId).collection("print_jobs")
        return colRef
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    CloudPrintJobDto::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).map { it.toEntity() }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, colRef.path)
                }
                throw error
            }
    }

    suspend fun recordPrintJob(job: PrintJobEntity): Result<String> {
        val uid = requireUserId()
        val jobId = "job_${UUID.randomUUID().toString().replace("-", "")}"
        val docRef = db.collection("users").document(uid).collection("print_jobs").document(jobId)
        val payload = mutableMapOf<String, Any>(
            "userId" to uid,
            "documentTitle" to job.documentTitle.ifBlank { "Document" }.take(200),
            "printerName" to job.printerName.ifBlank { "Printer" }.take(120),
            "connectionType" to job.connectionType.ifBlank { "Unknown" }.take(60),
            "protocolUsed" to job.protocolUsed.ifBlank { "AUTO" }.take(40),
            "paperSize" to job.paperSize.ifBlank { "A4" }.take(60),
            "pageCount" to job.pageCount.coerceIn(1, 10000),
            "copies" to job.copies.coerceIn(1, 99),
            "dpi" to job.dpi.coerceIn(72, 1200),
            "bytesTransferred" to job.bytesTransferred.coerceAtLeast(0L),
            "status" to if (job.status == "Failed") "Failed" else "Completed",
            "createdAt" to FieldValue.serverTimestamp()
        )
        if (!job.errorMessage.isNullOrBlank()) {
            payload["errorMessage"] = job.errorMessage.take(500)
        }

        return try {
            docRef.set(payload).await()
            Result.success(jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun clearPrintJobs(): Result<Unit> {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("print_jobs")
        return try {
            val snap = colRef.get().await()
            if (!snap.isEmpty) {
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, colRef.path)
            Result.failure(e)
        }
    }
}
