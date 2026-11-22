package app.pixelferry

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * The Pixel's TLS identity. The private key is created inside Android Keystore
 * (hardware-backed on Pixel XL) and can never be exported.
 */
object TlsIdentity {
    private const val ALIAS = "pixelferry-tls"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey(ks: KeyStore) {
        if (ks.containsAlias(ALIAS)) return
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=PixelFerry"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
            .setCertificateNotAfter(Date(System.currentTimeMillis() + 20L * 365 * 86_400_000L))
            .build())
        kpg.generateKeyPair()
    }

    fun sslContext(): SSLContext {
        val ks = keyStore(); ensureKey(ks)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    fun certDer(): ByteArray { val ks = keyStore(); ensureKey(ks); return ks.getCertificate(ALIAS).encoded }
}
