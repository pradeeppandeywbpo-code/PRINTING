package com.example.printer.usb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UsbPrinterManagerTest {

    @Test
    fun `scanConnectedPrinters returns empty list when no USB devices attached`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = UsbPrinterManager(context)
        manager.register()
        val printers = manager.scanConnectedPrinters()
        assertTrue(printers.isEmpty())
        manager.unregister()
    }

    @Test
    fun `openConnection and sendPrintData fail gracefully for missing device`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = UsbPrinterManager(context)

        assertFalse(manager.requestPermission("/dev/bus/usb/001/099"))

        val connResult = manager.openConnection("/dev/bus/usb/001/099")
        assertTrue(connResult.isFailure)

        val sendResult = manager.sendPrintData("/dev/bus/usb/001/099", byteArrayOf(0x1B, 0x40))
        assertTrue(sendResult.isFailure)
    }
}
