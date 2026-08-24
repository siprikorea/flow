package flow.view

/**
 * What the dotted numbers mean.
 *
 * Carried here rather than looked up over the network. Drawing happens on every resize, scroll and
 * click, so a lookup that waited on a server would undo the responsiveness this view depends on;
 * the list of OIDs in someone's certificate is also a fair description of what they are inspecting,
 * and that is not worth sending anywhere. A table costs tens of kilobytes and answers instantly,
 * offline, and to nobody else.
 *
 * An OID that is not here shows as its number, which is exactly what it is. That is why this only
 * carries names that are certain: a number tells you nothing, but a wrong name tells you something
 * false, and a wrong algorithm name in a certificate is the kind of false that gets acted on.
 */
internal object Oids {

    fun name(dotted: String): String? = TABLE[dotted]

    private val TABLE = buildMap {
        naming()
        extensions()
        pkix()
        rsa()
        cms()
        passwordBased()
        digests()
        macs()
        ciphers()
        signatures()
        ellipticCurves()
        korean()
    }

    /* ───────── X.520 naming attributes ───────── */

    private fun MutableMap<String, String>.naming() {
        put("2.5.4.0", "objectClass")
        put("2.5.4.3", "commonName")
        put("2.5.4.4", "surname")
        put("2.5.4.5", "serialNumber")
        put("2.5.4.6", "countryName")
        put("2.5.4.7", "localityName")
        put("2.5.4.8", "stateOrProvinceName")
        put("2.5.4.9", "streetAddress")
        put("2.5.4.10", "organizationName")
        put("2.5.4.11", "organizationalUnitName")
        put("2.5.4.12", "title")
        put("2.5.4.13", "description")
        put("2.5.4.14", "searchGuide")
        put("2.5.4.15", "businessCategory")
        put("2.5.4.16", "postalAddress")
        put("2.5.4.17", "postalCode")
        put("2.5.4.18", "postOfficeBox")
        put("2.5.4.20", "telephoneNumber")
        put("2.5.4.23", "facsimileTelephoneNumber")
        put("2.5.4.41", "name")
        put("2.5.4.42", "givenName")
        put("2.5.4.43", "initials")
        put("2.5.4.44", "generationQualifier")
        put("2.5.4.45", "uniqueIdentifier")
        put("2.5.4.46", "dnQualifier")
        put("2.5.4.65", "pseudonym")
        put("2.5.4.97", "organizationIdentifier")
        put("0.9.2342.19200300.100.1.1", "userId")
        put("0.9.2342.19200300.100.1.25", "domainComponent")
        put("1.2.840.113549.1.9.1", "emailAddress")
    }

    /* ───────── X.509 certificate and CRL extensions ───────── */

    private fun MutableMap<String, String>.extensions() {
        put("2.5.29.9", "subjectDirectoryAttributes")
        put("2.5.29.14", "subjectKeyIdentifier")
        put("2.5.29.15", "keyUsage")
        put("2.5.29.16", "privateKeyUsagePeriod")
        put("2.5.29.17", "subjectAltName")
        put("2.5.29.18", "issuerAltName")
        put("2.5.29.19", "basicConstraints")
        put("2.5.29.20", "cRLNumber")
        put("2.5.29.21", "cRLReason")
        put("2.5.29.23", "holdInstructionCode")
        put("2.5.29.24", "invalidityDate")
        put("2.5.29.27", "deltaCRLIndicator")
        put("2.5.29.28", "issuingDistributionPoint")
        put("2.5.29.29", "certificateIssuer")
        put("2.5.29.30", "nameConstraints")
        put("2.5.29.31", "cRLDistributionPoints")
        put("2.5.29.32", "certificatePolicies")
        put("2.5.29.32.0", "anyPolicy")
        put("2.5.29.33", "policyMappings")
        put("2.5.29.35", "authorityKeyIdentifier")
        put("2.5.29.36", "policyConstraints")
        put("2.5.29.37", "extKeyUsage")
        put("2.5.29.37.0", "anyExtendedKeyUsage")
        put("2.5.29.46", "freshestCRL")
        put("2.5.29.54", "inhibitAnyPolicy")
        put("2.16.840.1.113730.1.1", "netscape-cert-type")
        put("2.16.840.1.113730.1.13", "netscape-comment")
    }

    /* ───────── PKIX ───────── */

    private fun MutableMap<String, String>.pkix() {
        put("1.3.6.1.5.5.7.1.1", "authorityInfoAccess")
        put("1.3.6.1.5.5.7.1.2", "biometricInfo")
        put("1.3.6.1.5.5.7.1.3", "qcStatements")
        put("1.3.6.1.5.5.7.1.11", "subjectInfoAccess")
        put("1.3.6.1.5.5.7.1.24", "tlsFeature")
        put("1.3.6.1.5.5.7.2.1", "cps")
        put("1.3.6.1.5.5.7.2.2", "unotice")
        put("1.3.6.1.5.5.7.3.1", "serverAuth")
        put("1.3.6.1.5.5.7.3.2", "clientAuth")
        put("1.3.6.1.5.5.7.3.3", "codeSigning")
        put("1.3.6.1.5.5.7.3.4", "emailProtection")
        put("1.3.6.1.5.5.7.3.5", "ipsecEndSystem")
        put("1.3.6.1.5.5.7.3.6", "ipsecTunnel")
        put("1.3.6.1.5.5.7.3.7", "ipsecUser")
        put("1.3.6.1.5.5.7.3.8", "timeStamping")
        put("1.3.6.1.5.5.7.3.9", "OCSPSigning")
        put("1.3.6.1.5.5.7.48.1", "ocsp")
        put("1.3.6.1.5.5.7.48.1.1", "basicOCSPResponse")
        put("1.3.6.1.5.5.7.48.1.2", "ocspNonce")
        put("1.3.6.1.5.5.7.48.1.5", "ocspNoCheck")
        put("1.3.6.1.5.5.7.48.2", "caIssuers")
        put("1.3.6.1.5.5.7.48.3", "timeStamping (AIA)")
        put("1.3.6.1.4.1.11129.2.4.2", "signedCertificateTimestampList")
        put("1.3.6.1.4.1.11129.2.4.3", "ctPrecertificatePoison")
    }

    /* ───────── PKCS#1: RSA ───────── */

    private fun MutableMap<String, String>.rsa() {
        put("1.2.840.113549.1.1.1", "rsaEncryption")
        put("1.2.840.113549.1.1.2", "md2WithRSAEncryption")
        put("1.2.840.113549.1.1.3", "md4WithRSAEncryption")
        put("1.2.840.113549.1.1.4", "md5WithRSAEncryption")
        put("1.2.840.113549.1.1.5", "sha1WithRSAEncryption")
        put("1.2.840.113549.1.1.7", "RSAES-OAEP")
        put("1.2.840.113549.1.1.8", "mgf1")
        put("1.2.840.113549.1.1.9", "pSpecified")
        put("1.2.840.113549.1.1.10", "RSASSA-PSS")
        put("1.2.840.113549.1.1.11", "sha256WithRSAEncryption")
        put("1.2.840.113549.1.1.12", "sha384WithRSAEncryption")
        put("1.2.840.113549.1.1.13", "sha512WithRSAEncryption")
        put("1.2.840.113549.1.1.14", "sha224WithRSAEncryption")
        put("1.2.840.113549.1.1.15", "sha512-224WithRSAEncryption")
        put("1.2.840.113549.1.1.16", "sha512-256WithRSAEncryption")
        // the OIW branch, still seen on certificates old enough
        put("1.3.14.3.2.29", "sha1WithRSASignature")
    }

    /* ───────── PKCS#7 / CMS ───────── */

    private fun MutableMap<String, String>.cms() {
        put("1.2.840.113549.1.7.1", "data")
        put("1.2.840.113549.1.7.2", "signedData")
        put("1.2.840.113549.1.7.3", "envelopedData")
        put("1.2.840.113549.1.7.4", "signedAndEnvelopedData")
        put("1.2.840.113549.1.7.5", "digestedData")
        put("1.2.840.113549.1.7.6", "encryptedData")
        put("1.2.840.113549.1.9.16.1.1", "receipt")
        put("1.2.840.113549.1.9.16.1.2", "authData")
        put("1.2.840.113549.1.9.16.1.4", "tstInfo")
        put("1.2.840.113549.1.9.16.1.9", "compressedData")
        put("1.2.840.113549.1.9.16.1.23", "authEnvelopedData")
        put("1.2.840.113549.1.9.16.3.6", "cms3DESwrap")
        put("1.2.840.113549.1.9.16.3.7", "cmsRC2wrap")
        put("1.2.840.113549.1.9.16.3.8", "zlibCompress")
        put("1.2.840.113549.1.9.16.3.9", "pwri-KEK")
        put("1.2.840.113549.1.9.16.3.18", "chacha20-poly1305")

        // PKCS#9 attributes
        put("1.2.840.113549.1.9.2", "unstructuredName")
        put("1.2.840.113549.1.9.3", "contentType")
        put("1.2.840.113549.1.9.4", "messageDigest")
        put("1.2.840.113549.1.9.5", "signingTime")
        put("1.2.840.113549.1.9.6", "counterSignature")
        put("1.2.840.113549.1.9.7", "challengePassword")
        put("1.2.840.113549.1.9.8", "unstructuredAddress")
        put("1.2.840.113549.1.9.14", "extensionRequest")
        put("1.2.840.113549.1.9.15", "smimeCapabilities")
        put("1.2.840.113549.1.9.20", "friendlyName")
        put("1.2.840.113549.1.9.21", "localKeyId")
        put("1.2.840.113549.1.9.22.1", "x509Certificate")
        put("1.2.840.113549.1.9.23.1", "x509Crl")

        // PKCS#12 bags
        put("1.2.840.113549.1.12.10.1.1", "keyBag")
        put("1.2.840.113549.1.12.10.1.2", "pkcs8ShroudedKeyBag")
        put("1.2.840.113549.1.12.10.1.3", "certBag")
        put("1.2.840.113549.1.12.10.1.4", "crlBag")
        put("1.2.840.113549.1.12.10.1.5", "secretBag")
        put("1.2.840.113549.1.12.10.1.6", "safeContentsBag")
    }

    /* ───────── password-based encryption and key derivation ───────── */

    private fun MutableMap<String, String>.passwordBased() {
        put("1.2.840.113549.1.5.1", "pbeWithMD2AndDES-CBC")
        put("1.2.840.113549.1.5.3", "pbeWithMD5AndDES-CBC")
        put("1.2.840.113549.1.5.4", "pbeWithMD2AndRC2-CBC")
        put("1.2.840.113549.1.5.6", "pbeWithMD5AndRC2-CBC")
        put("1.2.840.113549.1.5.10", "pbeWithSHA1AndDES-CBC")
        put("1.2.840.113549.1.5.11", "pbeWithSHA1AndRC2-CBC")
        put("1.2.840.113549.1.5.12", "PBKDF2")
        put("1.2.840.113549.1.5.13", "PBES2")
        put("1.2.840.113549.1.5.14", "PBMAC1")
        put("1.2.840.113549.1.12.1.1", "pbeWithSHAAnd128BitRC4")
        put("1.2.840.113549.1.12.1.2", "pbeWithSHAAnd40BitRC4")
        put("1.2.840.113549.1.12.1.3", "pbeWithSHAAnd3-KeyTripleDES-CBC")
        put("1.2.840.113549.1.12.1.4", "pbeWithSHAAnd2-KeyTripleDES-CBC")
        put("1.2.840.113549.1.12.1.5", "pbeWithSHAAnd128BitRC2-CBC")
        put("1.2.840.113549.1.12.1.6", "pbeWithSHAAnd40BitRC2-CBC")
        put("1.3.6.1.4.1.11591.4.11", "scrypt")
    }

    /* ───────── digests ───────── */

    private fun MutableMap<String, String>.digests() {
        put("1.2.840.113549.2.2", "md2")
        put("1.2.840.113549.2.4", "md4")
        put("1.2.840.113549.2.5", "md5")
        put("1.3.14.3.2.26", "sha1")
        put("1.3.36.3.2.1", "ripemd160")
        put("1.3.36.3.2.2", "ripemd128")
        put("1.3.36.3.2.3", "ripemd256")
        put("2.16.840.1.101.3.4.2.1", "sha-256")
        put("2.16.840.1.101.3.4.2.2", "sha-384")
        put("2.16.840.1.101.3.4.2.3", "sha-512")
        put("2.16.840.1.101.3.4.2.4", "sha-224")
        put("2.16.840.1.101.3.4.2.5", "sha-512/224")
        put("2.16.840.1.101.3.4.2.6", "sha-512/256")
        put("2.16.840.1.101.3.4.2.7", "sha3-224")
        put("2.16.840.1.101.3.4.2.8", "sha3-256")
        put("2.16.840.1.101.3.4.2.9", "sha3-384")
        put("2.16.840.1.101.3.4.2.10", "sha3-512")
        put("2.16.840.1.101.3.4.2.11", "shake128")
        put("2.16.840.1.101.3.4.2.12", "shake256")
    }

    /* ───────── message authentication ───────── */

    private fun MutableMap<String, String>.macs() {
        put("1.2.840.113549.2.7", "hmacWithSHA1")
        put("1.2.840.113549.2.8", "hmacWithSHA224")
        put("1.2.840.113549.2.9", "hmacWithSHA256")
        put("1.2.840.113549.2.10", "hmacWithSHA384")
        put("1.2.840.113549.2.11", "hmacWithSHA512")
    }

    /* ───────── symmetric ciphers ───────── */

    private fun MutableMap<String, String>.ciphers() {
        put("1.3.14.3.2.6", "desECB")
        put("1.3.14.3.2.7", "desCBC")
        put("1.3.14.3.2.8", "desOFB")
        put("1.3.14.3.2.9", "desCFB")
        put("1.3.14.3.2.17", "desEDE")
        put("1.2.840.113549.3.2", "rc2CBC")
        put("1.2.840.113549.3.4", "rc4")
        put("1.2.840.113549.3.7", "des-EDE3-CBC")

        // NIST AES, which numbers each key size in its own block of twenty
        listOf(128 to 1, 192 to 21, 256 to 41).forEach { (bits, base) ->
            put("2.16.840.1.101.3.4.1.$base", "aes$bits-ECB")
            put("2.16.840.1.101.3.4.1.${base + 1}", "aes$bits-CBC")
            put("2.16.840.1.101.3.4.1.${base + 2}", "aes$bits-OFB")
            put("2.16.840.1.101.3.4.1.${base + 3}", "aes$bits-CFB")
            put("2.16.840.1.101.3.4.1.${base + 4}", "aes$bits-wrap")
            put("2.16.840.1.101.3.4.1.${base + 5}", "aes$bits-GCM")
            put("2.16.840.1.101.3.4.1.${base + 6}", "aes$bits-CCM")
            put("2.16.840.1.101.3.4.1.${base + 7}", "aes$bits-wrap-pad")
        }

        put("1.2.392.200011.61.1.1.1.2", "camellia128-CBC")
        put("1.2.392.200011.61.1.1.1.3", "camellia192-CBC")
        put("1.2.392.200011.61.1.1.1.4", "camellia256-CBC")
    }

    /* ───────── signature and key-agreement algorithms ───────── */

    private fun MutableMap<String, String>.signatures() {
        put("1.2.840.10040.4.1", "dsa")
        put("1.2.840.10040.4.3", "dsa-with-sha1")
        put("1.3.14.3.2.27", "dsaWithSHA1 (OIW)")
        put("2.16.840.1.101.3.4.3.1", "dsa-with-sha224")
        put("2.16.840.1.101.3.4.3.2", "dsa-with-sha256")
        put("2.16.840.1.101.3.4.3.3", "dsa-with-sha384")
        put("2.16.840.1.101.3.4.3.4", "dsa-with-sha512")
        put("2.16.840.1.101.3.4.3.5", "dsa-with-sha3-224")
        put("2.16.840.1.101.3.4.3.6", "dsa-with-sha3-256")
        put("2.16.840.1.101.3.4.3.7", "dsa-with-sha3-384")
        put("2.16.840.1.101.3.4.3.8", "dsa-with-sha3-512")
        put("2.16.840.1.101.3.4.3.9", "ecdsa-with-sha3-224")
        put("2.16.840.1.101.3.4.3.10", "ecdsa-with-sha3-256")
        put("2.16.840.1.101.3.4.3.11", "ecdsa-with-sha3-384")
        put("2.16.840.1.101.3.4.3.12", "ecdsa-with-sha3-512")
        put("2.16.840.1.101.3.4.3.13", "rsa-with-sha3-224")
        put("2.16.840.1.101.3.4.3.14", "rsa-with-sha3-256")
        put("2.16.840.1.101.3.4.3.15", "rsa-with-sha3-384")
        put("2.16.840.1.101.3.4.3.16", "rsa-with-sha3-512")

        put("1.2.840.10045.4.1", "ecdsa-with-SHA1")
        put("1.2.840.10045.4.3.1", "ecdsa-with-SHA224")
        put("1.2.840.10045.4.3.2", "ecdsa-with-SHA256")
        put("1.2.840.10045.4.3.3", "ecdsa-with-SHA384")
        put("1.2.840.10045.4.3.4", "ecdsa-with-SHA512")

        put("1.2.840.113549.1.3.1", "dhKeyAgreement")
        put("1.2.840.10046.2.1", "dhpublicnumber")

        // EdDSA and the X25519/X448 key agreements (RFC 8410)
        put("1.3.101.110", "X25519")
        put("1.3.101.111", "X448")
        put("1.3.101.112", "Ed25519")
        put("1.3.101.113", "Ed448")

        // ShangMi, which Chinese certificates are built from
        put("1.2.156.10197.1.104", "sm4")
        put("1.2.156.10197.1.301", "sm2p256v1")
        put("1.2.156.10197.1.401", "sm3")
        put("1.2.156.10197.1.501", "sm2-with-sm3")
    }

    /* ───────── elliptic curves ───────── */

    private fun MutableMap<String, String>.ellipticCurves() {
        put("1.2.840.10045.2.1", "ecPublicKey")
        put("1.2.840.10045.1.1", "prime-field")
        put("1.2.840.10045.1.2", "characteristic-two-field")
        put("1.2.840.10045.3.1.1", "prime192v1 (P-192)")
        put("1.2.840.10045.3.1.2", "prime192v2")
        put("1.2.840.10045.3.1.3", "prime192v3")
        put("1.2.840.10045.3.1.4", "prime239v1")
        put("1.2.840.10045.3.1.5", "prime239v2")
        put("1.2.840.10045.3.1.6", "prime239v3")
        put("1.2.840.10045.3.1.7", "prime256v1 (P-256)")

        put("1.3.132.0.1", "sect163k1")
        put("1.3.132.0.6", "secp112r1")
        put("1.3.132.0.7", "secp112r2")
        put("1.3.132.0.8", "secp160r1")
        put("1.3.132.0.9", "secp160k1")
        put("1.3.132.0.10", "secp256k1")
        put("1.3.132.0.15", "sect163r2")
        put("1.3.132.0.16", "sect283k1")
        put("1.3.132.0.17", "sect283r1")
        put("1.3.132.0.26", "sect233k1")
        put("1.3.132.0.27", "sect233r1")
        put("1.3.132.0.28", "secp128r1")
        put("1.3.132.0.29", "secp128r2")
        put("1.3.132.0.30", "secp160r2")
        put("1.3.132.0.31", "secp192k1")
        put("1.3.132.0.32", "secp224k1")
        put("1.3.132.0.33", "secp224r1 (P-224)")
        put("1.3.132.0.34", "secp384r1 (P-384)")
        put("1.3.132.0.35", "secp521r1 (P-521)")
        put("1.3.132.0.36", "sect409k1")
        put("1.3.132.0.37", "sect409r1")
        put("1.3.132.0.38", "sect571k1")
        put("1.3.132.0.39", "sect571r1")

        // Brainpool, numbered r1/t1 in pairs
        listOf(160, 192, 224, 256, 320, 384, 512).forEachIndexed { i, bits ->
            put("1.3.36.3.3.2.8.1.1.${i * 2 + 1}", "brainpoolP${bits}r1")
            put("1.3.36.3.3.2.8.1.1.${i * 2 + 2}", "brainpoolP${bits}t1")
        }
    }

    /* ───────── KISA, for Korean certificates and signed data ───────── */

    private fun MutableMap<String, String>.korean() {
        put("1.2.410.200004.1.4", "seedCBC")
        put("1.2.410.200004.2.1", "has160")
        put("1.2.410.200004.4.3", "kcdsa-with-has160")
    }
}
