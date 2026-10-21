package io.olcos.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class Vp8TuningTest {
    @Test fun anMtsLinkLaneRunsAtSixtyFramesAndAtLeastSixtyFourPackets() {
        assertEquals(Vp8Tuning.Numbers(60, 64), Vp8Tuning.effective(listOf("mtslink"), 30, 64))
        assertEquals(Vp8Tuning.Numbers(60, 64), Vp8Tuning.effective(listOf("mtslink"), 5, 1))
        assertEquals(Vp8Tuning.Numbers(60, 64), Vp8Tuning.effective(listOf("mtslink"), 60, 63))
    }

    @Test fun aLargerBatchOfTheUserIsKept() {
        assertEquals(Vp8Tuning.Numbers(60, 128), Vp8Tuning.effective(listOf("mtslink"), 30, 128))
        assertEquals(Vp8Tuning.Numbers(60, 256), Vp8Tuning.effective(setOf("mtslink"), 60, 256))
    }

    @Test fun oneMtsLinkLaneAmongOthersLiftsTheWholeConnection() {
        assertEquals(Vp8Tuning.Numbers(60, 64), Vp8Tuning.effective(listOf("vkcalls", "mtslink", "jitsi"), 30, 32))
    }

    @Test fun everyOtherProtocolKeepsTheUsersNumbers() {
        for (carrier in Maintenance.CARRIERS - "mtslink") {
            assertEquals(carrier, Vp8Tuning.Numbers(30, 64), Vp8Tuning.effective(listOf(carrier), 30, 64))
            assertEquals(carrier, Vp8Tuning.Numbers(5, 1), Vp8Tuning.effective(listOf(carrier), 5, 1))
        }
        assertEquals(Vp8Tuning.Numbers(12, 3), Vp8Tuning.effective(emptyList(), 12, 3))
    }
}
