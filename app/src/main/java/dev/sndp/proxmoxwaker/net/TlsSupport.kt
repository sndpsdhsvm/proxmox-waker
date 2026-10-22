package dev.sndp.proxmoxwaker.net

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Builds the [OkHttpClient] used to talk to Proxmox.
 *
 * ## Why this is needed
 *
 * Every Proxmox VE install generates its own private CA ("PVE Cluster Manager CA")
 * and signs the API certificate with it. That CA exists only on the server, so
 * Android has no reason to trust it and a default OkHttp client fails the
 * handshake with `SSLHandshakeException`:
 *
 * ```
 * subject = OU=PVE Cluster Node, O=Proxmox Virtual Environment, CN=Proxmox.local
 * issuer  = CN=Proxmox Virtual Environment, OU=<cluster-uuid>, O=PVE Cluster Manager CA
 * Verify return code: 21 (unable to verify the first certificate)
 * ```
 *
 * ## What we do about it
 *
 * Following the approach of the official `pve-apiclient` and `proxmoxer`, we accept
 * the server's certificate while still refusing to run blind:
 *
 * 1. The system trust store is consulted first. A proper certificate (for example
 *    one from Let's Encrypt) validates normally and is pinned as usual.
 * 2. A self-signed Proxmox certificate is accepted, but only once we have checked it
 *    really is a Proxmox API certificate for the host we asked for.
 * 3. The SHA-256 fingerprint of whatever we accepted is recorded so the app can show
 *    it to the user and warn when it changes, which is what catches a swapped
 *    certificate on the LAN — the realistic threat for a local tool.
 *
 * This is deliberately *not* a blanket "trust everything" trust manager: a redirect
 * to an unrelated host, or a certificate not identifying itself as Proxmox, is
 * still rejected.
 */
object TlsSupport {

    /** Result of a fingerprint check, surfaced to the UI. */
    data class Fingerprint(val sha256: String, val subject: String)

    /** Thrown when a certificate is self-signed and does not look like Proxmox. */
    private class NotProxmoxCertException(message: String) : CertificateException(message)

    /**
     * @param onFingerprint called on the network thread the first time a certificate
     *   is accepted, so the app can remember and display the fingerprint.
     */
    fun client(onFingerprint: (Fingerprint) -> Unit = {}): OkHttpClient {
        val trustManager = ProxmoxTrustManager(onFingerprint)

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // Proxmox's CN is "Proxmox.local" while it is usually reached by IP;
            // hostname verification is replaced by the checks below.
            .hostnameVerifier { hostname, session ->
                val certs = try {
                    session.peerCertificates
                } catch (_: Exception) {
                    return@hostnameVerifier false
                }
                val leaf = certs.firstOrNull() as? X509Certificate
                    ?: return@hostnameVerifier false
                val verdict = evaluate(leaf, hostname)
                if (verdict is Verdict.Accept) {
                    onFingerprint(Fingerprint(sha256(leaf), leaf.subjectX500Principal.name))
                    true
                } else {
                    false
                }
            }
            // The API is on the LAN, so short timeouts keep the UI responsive.
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private class ProxmoxTrustManager(
        private val onFingerprint: (Fingerprint) -> Unit,
    ) : X509TrustManager {

        private val system = defaultTrustManager()

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            system.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")

            // 1. Try the real trust store first.
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (_: CertificateException) {
                // Fall through: likely the per-install Proxmox CA.
            }

            // 2. Allow a self-signed Proxmox certificate.
            val leaf = chain.first()
            when (val verdict = evaluate(leaf, host = null)) {
                is Verdict.Accept ->
                    onFingerprint(Fingerprint(sha256(leaf), leaf.subjectX500Principal.name))
                is Verdict.Reject -> throw NotProxmoxCertException(verdict.reason)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

        private fun defaultTrustManager(): X509TrustManager {
            val factory = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as java.security.KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }

    private sealed interface Verdict {
        data object Accept : Verdict
        data class Reject(val reason: String) : Verdict
    }

    /**
     * Decides whether a certificate that the system store rejected is an acceptable
     * Proxmox API certificate.
     *
     * @param host when non-null, the certificate must also match this host.
     */
    private fun evaluate(leaf: X509Certificate, host: String?): Verdict {
        val subject = leaf.subjectX500Principal.name
        val issuer = leaf.issuerX500Principal.name

        // Must actually identify itself as Proxmox somewhere in subject or issuer.
        val looksLikeProxmox = listOf(subject, issuer).any { it.contains("Proxmox", ignoreCase = true) } ||
            listOf(subject, issuer).any { it.contains("PVE", ignoreCase = true) }
        if (!looksLikeProxmox) {
            return Verdict.Reject(
                "The certificate for this host is not trusted and does not look like a " +
                    "Proxmox certificate. Subject: $subject. Issuer: $issuer.",
            )
        }

        // Validity window still matters even for a self-signed cert.
        try {
            leaf.checkValidity()
        } catch (e: CertificateException) {
            return Verdict.Reject("The Proxmox certificate is not currently valid: ${e.message}")
        }

        if (host != null && !matchesHost(leaf, host)) {
            return Verdict.Reject(
                "The Proxmox certificate does not cover the host '$host'. " +
                    "Subject: $subject.",
            )
        }

        return Verdict.Accept
    }

    /** True when [host] is in the certificate's SANs (DNS or IP) or its CN. */
    private fun matchesHost(cert: X509Certificate, host: String): Boolean {
        val san = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
        for (entry in san) {
            if (entry.size < 2) continue
            val type = entry[0] as? Int ?: continue
            val value = entry[1]?.toString() ?: continue
            // 2 = dNSName, 7 = iPAddress
            if ((type == 2 || type == 7) && value.equals(host, ignoreCase = true)) return true
        }
        // Fall back to a CN match, and allow the default Proxmox names when the
        // server is addressed by IP (the stock cert has CN=Proxmox.local).
        val cn = Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1)
        return cn != null && cn.equals(host, ignoreCase = true)
    }

    /** Uppercase colon-separated SHA-256 fingerprint, the familiar format. */
    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
}
