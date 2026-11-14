package com.example.data.remote

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.local.PrintJobEntity
import com.example.domain.model.ConnectionType
import com.example.domain.model.DiscoveredPrinter
import com.example.domain.model.PrinterProtocol
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CloudPrintRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun saveAndObservePrinter_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudPrintRepository(firestore, auth)

        val uniqueKey = "raw:192.168.1.${(10..200).random()}:9100"
        val printer = DiscoveredPrinter(
            id = uniqueKey,
            name = "HP LaserJet Pro",
            connectionType = ConnectionType.WIFI_RAW,
            address = "192.168.1.50",
            port = 9100,
            defaultProtocol = PrinterProtocol.PCL_RASTER,
            details = "192.168.1.50:9100 · PCL5"
        )

        val saveResult = withTimeout(TIMEOUT_MS) { repo.savePrinter(printer) }
        assertTrue(saveResult.isSuccess)

        val printers = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSavedPrinters(uid).first { list -> list.any { it.id == uniqueKey } }
        }
        assertTrue(printers.any { it.name == "HP LaserJet Pro" })

        val delResult = withTimeout(TIMEOUT_MS) { repo.deletePrinter(uniqueKey) }
        assertTrue(delResult.isSuccess)
    }

    @Test
    fun recordAndObservePrintJob_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudPrintRepository(firestore, auth)
        val docTitle = "Contract_${UUID.randomUUID().toString().take(8)}.pdf"

        val job = PrintJobEntity(
            documentTitle = docTitle,
            printerName = "USB Thermal",
            connectionType = "USB OTG",
            protocolUsed = "ESC/POS",
            paperSize = "80mm Thermal Roll",
            pageCount = 1,
            copies = 1,
            dpi = 300,
            bytesTransferred = 14336L,
            status = "Completed"
        )

        val recordResult = withTimeout(TIMEOUT_MS) { repo.recordPrintJob(job) }
        assertTrue(recordResult.isSuccess)

        val jobs = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observePrintJobs(uid).first { list -> list.any { it.documentTitle == docTitle } }
        }
        assertEquals(1, jobs.count { it.documentTitle == docTitle })
    }

    @Test
    fun observeSavedPrinters_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        signInTestUser(BOB_EMAIL)
        val bobRepo = CloudPrintRepository(firestore, auth)

        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeSavedPrinters(aliceUid).first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        val fsEx = caught?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun observePrintJobs_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = CloudPrintRepository(firestore, auth)

        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observePrintJobs("some_uid_123").first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        val fsEx = caught?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_printforge@test.com"
        const val BOB_EMAIL = "bob_printforge@test.com"
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
