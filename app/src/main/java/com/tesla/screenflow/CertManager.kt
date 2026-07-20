package com.tesla.screenflow

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.*

/**
 * 为 code-prometheus.ai 生成自签名 SSL 证书。
 * SAN: code-prometheus.ai + 热点 IP 192.168.43.2
 */
object CertManager {

    private const val TAG = "TeslaScreenFlow::Cert"
    private const val DOMAIN = "code-prometheus.ai"
    private const val LOCAL_IP = "192.168.43.2"
    private const val KS_PASS = "tesla123"

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun getSslContext(context: Context): SSLContext {
        val ksFile = File(context.filesDir, "tesla-ssl.jks")

        if (ksFile.exists()) {
            val ks = KeyStore.getInstance("JKS")
            ks.load(ksFile.inputStream(), KS_PASS.toCharArray())
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, KS_PASS.toCharArray())
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }

        // Generate cert
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=$DOMAIN, O=teslaScreenFlow, C=CN")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 3650L * 86400000L)
        val bld = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.public)
        bld.addExtension(Extension.subjectAlternativeName, false, GeneralNames(arrayOf(
            GeneralName(GeneralName.dNSName, DOMAIN),
            GeneralName(GeneralName.iPAddress, LOCAL_IP)
        )))
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val cert: X509Certificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(bld.build(signer))

        val ks = KeyStore.getInstance("JKS")
        ks.load(null, KS_PASS.toCharArray())
        ks.setKeyEntry("tesla", keyPair.private, KS_PASS.toCharArray(), arrayOf(cert))
        ks.store(FileOutputStream(ksFile), KS_PASS.toCharArray())

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KS_PASS.toCharArray())
        Log.i(TAG, "Certificate generated for $DOMAIN")
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
}
