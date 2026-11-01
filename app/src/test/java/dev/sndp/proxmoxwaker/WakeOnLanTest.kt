package dev.sndp.proxmoxwaker

import dev.sndp.proxmoxwaker.net.WakeOnLan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class WakeOnLanTest {

    @Test
    fun `parses colon separated mac`() {
        val bytes = WakeOnLan.parseMac("AA:BB:CC:DD:EE:FF")
        assertArrayEquals(byteArrayOf(-86, -69, -52, -35, -18, -1), bytes)
    }

    @Test
    fun `parses dash separated mac`() {
        assertArrayEquals(
            WakeOnLan.parseMac("aa-bb-cc-dd-ee-ff"),
            WakeOnLan.parseMac("AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `parses bare hex mac`() {
        assertArrayEquals(
            WakeOnLan.parseMac("aabbccddeeff"),
            WakeOnLan.parseMac("AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `parses cisco style mac`() {
        assertArrayEquals(
            WakeOnLan.parseMac("aabb.ccdd.eeff"),
            WakeOnLan.parseMac("AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `rejects malformed mac`() {
        assertNull(WakeOnLan.parseMac(""))
        assertNull(WakeOnLan.parseMac("AA:BB:CC:DD:EE"))
        assertNull(WakeOnLan.parseMac("ZZ:BB:CC:DD:EE:FF"))
        assertNull(WakeOnLan.parseMac("AA:BB:CC:DD:EE:FF:00"))
    }

    @Test
    fun `magic packet has correct shape`() {
        val mac = WakeOnLan.parseMac("01:02:03:04:05:06")!!
        val packet = WakeOnLan.magicPacket(mac)

        assertEquals(102, packet.size)
        for (i in 0 until 6) {
            assertEquals("sync byte $i", 0xFF.toByte(), packet[i])
        }
        for (repeat in 0 until 16) {
            for (i in 0 until 6) {
                assertEquals(
                    "repeat $repeat byte $i",
                    mac[i],
                    packet[6 + repeat * 6 + i],
                )
            }
        }
    }

    @Test
    fun `formats mac back to canonical form`() {
        val mac = WakeOnLan.parseMac("aabbccddeeff")!!
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLan.formatMac(mac))
    }

    @Test
    fun `sends the magic packet over UDP`() = runBlocking {
        val macString = "01:02:03:04:05:06"
        val expected = WakeOnLan.magicPacket(WakeOnLan.parseMac(macString)!!)

        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 2_000
            WakeOnLan.send(macString, "127.0.0.1", receiver.localPort)

            val buffer = ByteArray(expected.size)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)
            assertArrayEquals(expected, buffer.copyOf(packet.length))
        }
    }
}
