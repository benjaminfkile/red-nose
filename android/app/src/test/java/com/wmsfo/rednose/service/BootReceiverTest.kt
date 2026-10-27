package com.wmsfo.rednose.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// red-nose.md 5.1: the receiver starts the service at boot and after a package
// update when an enrollment exists, and never otherwise.
class BootReceiverTest {
    private val boot = "android.intent.action.BOOT_COMPLETED"
    private val lockedBoot = "android.intent.action.LOCKED_BOOT_COMPLETED"
    private val replaced = "android.intent.action.MY_PACKAGE_REPLACED"

    @Test
    fun startsOnEachActionWithEnrollment() {
        for (action in listOf(boot, lockedBoot, replaced)) {
            assertTrue(action, BootReceiver.shouldStart(action, hasEnrollment = true))
        }
    }

    @Test
    fun staysStoppedOnEachActionWithoutEnrollment() {
        for (action in listOf(boot, lockedBoot, replaced)) {
            assertFalse(action, BootReceiver.shouldStart(action, hasEnrollment = false))
        }
    }

    @Test
    fun ignoresAnUnrelatedAction() {
        val other = "android.intent.action.PACKAGE_REPLACED"
        assertFalse(BootReceiver.shouldStart(other, hasEnrollment = true))
        assertFalse(BootReceiver.shouldStart(other, hasEnrollment = false))
        assertFalse(BootReceiver.shouldStart(null, hasEnrollment = true))
    }
}
