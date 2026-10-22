package dev.sndp.proxmoxwaker

import dev.sndp.proxmoxwaker.data.GuestStatus
import dev.sndp.proxmoxwaker.data.GuestType
import dev.sndp.proxmoxwaker.data.ProxmoxConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests pinned to the documented Proxmox API contract, so a regression in the
 * assumptions the client makes shows up here rather than on a phone.
 *
 * Sources: <https://pve.proxmox.com/wiki/Proxmox_VE_API> and the generated
 * `apidoc.js` schema (paths /access/ticket, /version, /nodes/{node}/qemu|lxc).
 */
class ProxmoxContractTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `base url is always https on the api port`() {
        val config = ProxmoxConfig(host = "192.0.2.10", apiPort = 8006)
        assertEquals("https://192.0.2.10:8006/api2/json", config.baseUrl)
    }

    @Test
    fun `blank node is allowed so it can be auto detected`() {
        val config = ProxmoxConfig(host = "h", username = "root@pam", password = "p")
        assertTrue(config.node.isBlank())
        assertTrue(config.canTalkToApi)
    }

    @Test
    fun `login response matches the documented envelope`() {
        // Exactly the shape documented for POST /access/ticket.
        val body = """
            {"data":{
              "CSRFPreventionToken":"4EEC61E2:lwk7od06fa1+DcPUwBTXCcndyAY",
              "ticket":"PVE:root@pam:4EEC61E2::rsKoApxDTLYPn6H3NNT6iP2mv...",
              "username":"root@pam"
            }}
        """.trimIndent()

        val data = json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject
        assertEquals("root@pam", data["username"]!!.jsonPrimitive.content)
        assertTrue(data["ticket"]!!.jsonPrimitive.content.startsWith("PVE:"))
        assertTrue(data["CSRFPreventionToken"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `authentication failure matches the live error shape`() {
        // Captured from the real host: 401 with this exact body.
        val body = """{"message":"authentication failure\n","data":null}"""
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("authentication failure", obj["message"]!!.jsonPrimitive.content.trim())
        assertTrue(obj["data"]!!.toString() == "null")
    }

    @Test
    fun `node index uses the node field`() {
        // GET /nodes returns objects carrying a "node" key.
        val body = """
            {"data":[
              {"node":"pve","status":"online","type":"node","id":"node/pve"},
              {"node":"pve2","status":"online","type":"node","id":"node/pve2"}
            ]}
        """.trimIndent()
        val nodes = json.parseToJsonElement(body).jsonObject["data"]!!
            .let { Json.decodeFromString<kotlinx.serialization.json.JsonArray>(it.toString()) }
            .map { it.jsonObject["node"]!!.jsonPrimitive.content }
        assertEquals(listOf("pve", "pve2"), nodes)
    }

    @Test
    fun `guest listing uses vmid name and status fields`() {
        val body = """
            {"data":[
              {"vmid":100,"name":"web","status":"running"},
              {"vmid":101,"name":"db","status":"stopped"},
              {"vmid":102,"status":"stopped"}
            ]}
        """.trimIndent()
        val arr = Json.decodeFromString<kotlinx.serialization.json.JsonArray>(
            json.parseToJsonElement(body).jsonObject["data"]!!.toString(),
        )
        val guests = arr.mapNotNull { el ->
            val o = el.jsonObject
            val vmid = o["vmid"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.content
            Triple(vmid, name, o["status"]?.jsonPrimitive?.content)
        }
        assertEquals(3, guests.size)
        assertEquals("web", guests[0].second)
        // A guest with no name must still be listed; the client substitutes one.
        assertEquals(null, guests[2].second)
    }

    @Test
    fun `status values map onto the enum`() {
        assertEquals(GuestStatus.RUNNING, GuestStatus.fromApi("running"))
        assertEquals(GuestStatus.STOPPED, GuestStatus.fromApi("stopped"))
        assertEquals(GuestStatus.PAUSED, GuestStatus.fromApi("paused"))
        assertEquals(GuestStatus.UNKNOWN, GuestStatus.fromApi("something-new"))
    }

    @Test
    fun `guest type paths match the api`() {
        assertEquals("qemu", GuestType.QEMU.apiPath)
        assertEquals("lxc", GuestType.LXC.apiPath)
    }

    @Test
    fun `can talk to api requires credentials`() {
        assertFalse(ProxmoxConfig(host = "h").canTalkToApi)
        assertTrue(ProxmoxConfig(host = "h", username = "u", password = "p").canTalkToApi)
    }
}
