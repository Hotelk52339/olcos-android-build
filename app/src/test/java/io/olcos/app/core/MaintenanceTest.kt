package io.olcos.app.core

import io.olcos.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the protocol choice allows and what reaches the server's scripts:
 * MTS Link carries vp8channel alone, its room is pasted, and the server gets
 * its link with the typed ICE hint (else `both`). Pure: no agent, no assets.
 */
class MaintenanceTest {
    private val meeting = "https://my.mts-link.ru/j/100200300/400500600700"

    @Test fun mtsLinkIsOfferedAndBringsItsRoom() {
        assertTrue("mtslink" in Maintenance.CARRIERS)
        assertTrue("mtslink" in Maintenance.ROOM_REQUIRED)
    }

    @Test fun mtsLinkCarriesVp8ChannelAlone() {
        assertEquals(listOf("vp8channel"), Maintenance.allowedTransports("mtslink"))
    }

    @Test fun everyOtherProtocolIsOfferedEveryTransport() {
        for (carrier in Maintenance.CARRIERS - "mtslink") {
            assertEquals(carrier, Maintenance.TRANSPORTS, Maintenance.allowedTransports(carrier))
        }
    }

    @Test fun pickingMtsLinkSwitchesToVp8Channel() {
        for (current in Maintenance.TRANSPORTS) {
            assertEquals(current, "vp8channel", Maintenance.transportFor("mtslink", current))
        }
    }

    @Test fun pickingAnotherProtocolKeepsTheTransport() {
        for (carrier in Maintenance.CARRIERS - "mtslink") {
            for (current in Maintenance.TRANSPORTS) {
                assertEquals("$carrier/$current", current, Maintenance.transportFor(carrier, current))
            }
        }
    }

    @Test fun aRoomChangeGivesTheServerTheTypedIceHint() {
        val script = Maintenance.reconfigureScript("olcrtc-server-ab12", "mtslink", "vp8channel", "$meeting?ice=tcp")
        assertTrue(script.contains("id: \"$meeting?ice=tcp\""))
        assertTrue(script.contains("@$meeting?ice=tcp#"))
        assertFalse(script.contains("ice=both"))
    }

    @Test fun aRoomChangeWithoutAHintGivesTheServerBoth() {
        val script = Maintenance.reconfigureScript("olcrtc-server-ab12", "mtslink", "vp8channel", meeting)
        assertTrue(script.contains("id: \"$meeting?ice=both\""))
        assertTrue(script.contains("@$meeting?ice=both#"))
    }

    @Test fun aContainerReconfiguredToMtsLinkGetsSixtyFramesAndAtLeastSixtyFourPackets() {
        val script = Maintenance.reconfigureScript("olcrtc-server-ab12", "mtslink", "vp8channel", meeting, vp8FPS = 30, vp8Batch = 32)
        assertTrue(script.contains("sed -i '/^vp8:/,/^[^ ]/{s|^  fps: .*|  fps: 60|;s|^  batch_size: .*|  batch_size: 64|}' \"\$CONFIG\""))
        // A yaml with no vp8 block gets one, and the sed comes before the restart that reads it.
        assertTrue(script.contains("printf 'vp8:\\n  fps: 60\\n  batch_size: 64\\n' >> \"\$CONFIG\""))
        assertTrue(script.indexOf("^vp8:") < script.indexOf("podman restart \"\${CNAME}\""))
    }

    @Test fun aLargerBatchOfThePersonIsKeptOnReconfigure() {
        val script = Maintenance.reconfigureScript("olcrtc-server-ab12", "mtslink", "vp8channel", meeting, vp8FPS = 12, vp8Batch = 128)
        assertTrue(script.contains("  fps: 60|"))
        assertTrue(script.contains("  batch_size: 128|"))
    }

    @Test fun reconfigureLeavesTheVp8BlockAloneForEveryoneElse() {
        for ((carrier, transport) in listOf("vkcalls" to "vp8channel", "jitsi" to "datachannel", "mtslink" to "datachannel")) {
            val script = Maintenance.reconfigureScript("olcrtc-server-ab12", carrier, transport, "room")
            assertFalse("$carrier/$transport", script.contains("^vp8:"))
        }
        assertEquals(emptyList<String>(), Maintenance.vp8TuningLines("vkcalls", "vp8channel", 30, 64))
    }

    @Test fun aReconfigureDropsATokenLineTheContainerCarried() {
        // A container an earlier version set up for WB Stream keeps working as it is; changing its room cannot restore the token.
        val script = Maintenance.reconfigureScript("olcrtc-server-ab12", "jitsi", "datachannel", "room")
        assertTrue(script.contains("-e '/^  token: /d'"))
        assertFalse(script.contains("token: \""))
    }

    @Test fun anotherServiceGetsItsRoomAsItIs() {
        val script = Maintenance.reconfigureScript("olcrtc-server-ab12", "vkcalls", "vp8channel", "https://vk.ru/call/join/Ab-1")
        assertTrue(script.contains("id: \"https://vk.ru/call/join/Ab-1\""))
    }

    // MARK: the make-a-room button

    @Test fun everyProtocolTheAppMakesRoomsForOffersOne() {
        for (carrier in listOf("jitsi", "telemost", "vkcalls", "salutejazz")) {
            assertTrue(carrier, Maintenance.canMakeRoom(carrier))
        }
    }

    @Test fun wbStreamIsNoLongerOfferedAnywhere() {
        assertFalse("wbstream" in Maintenance.CARRIERS)
        assertFalse("wbstream" in Maintenance.ROOM_REQUIRED)
        assertFalse(Maintenance.canMakeRoom("wbstream"))
    }

    @Test fun mtsLinkAlwaysOffersTheButton() {
        // Learned and signed in, the room is made silently; otherwise the sign-in sheet opens and the app takes the link made there.
        assertTrue(Maintenance.canMakeRoom("mtslink"))
    }

    @Test fun everyProtocolHasItsOwnRoomWording() {
        // The Jitsi wording names Jitsi alone: no other protocol shows it.
        assertEquals(R.string.add_server_room_jitsi, Maintenance.roomHintFor("jitsi"))
        assertEquals(R.string.room_placeholder_jitsi, Maintenance.roomPlaceholderFor("jitsi"))
        for (carrier in Maintenance.CARRIERS - "jitsi") {
            assertNotEquals(carrier, R.string.add_server_room_jitsi, Maintenance.roomHintFor(carrier))
            assertNotEquals(carrier, R.string.room_placeholder_jitsi, Maintenance.roomPlaceholderFor(carrier))
        }
    }
}
