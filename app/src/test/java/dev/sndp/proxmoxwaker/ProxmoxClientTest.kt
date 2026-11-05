package dev.sndp.proxmoxwaker

import dev.sndp.proxmoxwaker.data.ProxmoxConfig
import dev.sndp.proxmoxwaker.net.ProxmoxClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contract tests for the write endpoints, using a fake HTTP layer. */
class ProxmoxClientTest {

    @Test
    fun `shutdown node posts command to node status endpoint`() = runBlocking {
        val requests = mutableListOf<Request>()

        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val body = when (request.url.encodedPath) {
                    "/api2/json/access/ticket" ->
                        """{"data":{"ticket":"ticket","CSRFPreventionToken":"csrf","username":"root@pam"}}"""
                    "/api2/json/nodes" ->
                        """{"data":[{"node":"pve"}]}"""
                    else ->
                        """{"data":"UPID:pve:00000000:00000000:00000000:qmstart:100:root@pam:"}"""
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val client = ProxmoxClient(http)
        client.shutdownNode(
            ProxmoxConfig(
                host = "192.0.2.10",
                node = "pve",
                username = "root@pam",
                password = "secret",
            ),
        )

        val statusRequest = requests.last()
        assertEquals("POST", statusRequest.method)
        assertEquals("/api2/json/nodes/pve/status", statusRequest.url.encodedPath)
        assertEquals("csrf", statusRequest.header("CSRFPreventionToken"))

        val body = Buffer()
        statusRequest.body?.writeTo(body)
        assertEquals("command=shutdown", body.readUtf8())
    }

    @Test
    fun `forgetSession clears the resolved node`() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = when (request.url.encodedPath) {
                    "/api2/json/access/ticket" ->
                        """{"data":{"ticket":"ticket","CSRFPreventionToken":"csrf","username":"root@pam"}}"""
                    "/api2/json/version" ->
                        """{"data":{"version":"8.2.4"}}"""
                    "/api2/json/nodes" ->
                        """{"data":[{"node":"pve"}]}"""
                    else -> """{"data":{}}"""
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val client = ProxmoxClient(http)
        client.testConnection(
            ProxmoxConfig(
                host = "192.0.2.10",
                node = "pve",
                username = "root@pam",
                password = "secret",
            ),
        )
        assertEquals("pve", client.resolvedNode)

        client.forgetSession()

        assertNull(client.resolvedNode)
    }
}
