package com.garathon.flatdroid.launcher.electron

import com.garathon.flatdroid.setup.rootfs.TarExtractor
import com.garathon.flatdroid.util.WHITESPACE
import com.garathon.flatdroid.util.base64
import com.garathon.flatdroid.util.fromBase64
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPInputStream

/**
 * A second source for an Electron release's checksums: npm's `electron` package ships
 * `checksums.json` (Electron 15 and later), and npm signs every package's integrity (its tarball's
 * SHA-512) with a registry key pinned in [KEYS]. A forged one needs npm's key as well as GitHub's
 * release, not only a broken TLS connection.
 */
object NpmChecksums {
    sealed interface Result {
        /** npm's signed checksum is the one GitHub's SHASUMS256.txt gave. */
        data object Match : Result
        /** npm says otherwise, or its package fails its own signature or integrity: the download can't be trusted. */
        data class Mismatch(val reason: String) : Result
        /** No answer from npm: GitHub's checksum is all there is. */
        data class Skipped(val reason: String) : Result
    }

    /**
     * npm's registry keys (`https://registry.npmjs.org/-/npm/v1/keys`), by key ID: SubjectPublicKeyInfo,
     * ECDSA P-256. The first expired on 2025-01-29 and still signs what was published before; its
     * signatures are taken without a date check (the date is only in the package's whole history, about 1 MB).
     */
    val KEYS = mapOf(
        "SHA256:jl3bwswu80PjjokCgh0o2w5c2U4LhQAE57gj9cz1kzA" to
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Olb3zMAFFxXKHiIkQO5cJ3Yhl5i6UPp+IhuteBJbuHcA5UogKo0EWtlWwW6KSaKoTNEYL7JlCQiVnkhBktUgg==",
        "SHA256:DhQ8wR5APBvFHLF/+Tc+AYvPOdTpcIDqOhxsBHRwC7U" to
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEY6Ya7W++7aUPzvMTrezH6Ycx3c+HOKYCcNGybJZSCJq/fd7Qa8uuAKtdIkUQtQiEKERhAmE5lMMJhP8OkDOa2g==",
    )

    private const val PACKAGE = "electron"
    private const val CHECKSUMS = "package/checksums.json"
    private const val MAX_DOCUMENT_BYTES = 1L shl 20
    /** The package is about 200 KiB packed. */
    private const val MAX_TARBALL_BYTES = 16L shl 20
    private const val MAX_CHECKSUMS_BYTES = 1L shl 20

    fun versionUrl(version: String) = "https://registry.npmjs.org/$PACKAGE/$version"

    /** Built here rather than taken from the version's document: the signature doesn't cover that URL. */
    fun tarballUrl(version: String) = "https://registry.npmjs.org/$PACKAGE/-/$PACKAGE-$version.tgz"

    /**
     * Checks [expected], [zipName]'s SHA-256 from GitHub, against npm's package of Electron
     * [version], downloading its version document and tarball into [dir] with [download].
     */
    fun check(
        version: String,
        zipName: String,
        expected: String,
        dir: File,
        download: (url: String, dest: File, maxBytes: Long) -> Unit,
        keys: Map<String, String> = KEYS,
    ): Result {
        val document = File(dir, "npm-$PACKAGE-$version.json")
        val tarball = File(dir, "npm-$PACKAGE-$version.tgz")
        try {
            if (!document.isFile) download(versionUrl(version), document, MAX_DOCUMENT_BYTES)
        } catch (e: IOException) {
            return Result.Skipped("npm didn't answer: ${e.message}")
        }
        val dist = try {
            dist(document.readText())
        } catch (e: JSONException) {
            null
        } ?: return Result.Skipped("npm's document for $PACKAGE@$version has no integrity")

        val signed = dist.signatures.firstOrNull { it.first in keys }
            ?: return Result.Skipped("$PACKAGE@$version is signed only by keys Flatdroid doesn't know: ${dist.signatures.map { it.first }}")
        if (!verifies(PACKAGE, version, dist.integrity, signed.second, keys.getValue(signed.first))) {
            return Result.Mismatch("npm's signature of $PACKAGE@$version doesn't verify")
        }

        try {
            if (!tarball.isFile) download(tarballUrl(version), tarball, MAX_TARBALL_BYTES)
        } catch (e: IOException) {
            return Result.Skipped("npm's package didn't download: ${e.message}")
        }
        if (!integrityMatches(dist.integrity, tarball)) {
            tarball.delete()
            return Result.Mismatch("npm's package of $PACKAGE@$version isn't the one npm signed")
        }

        val json = GZIPInputStream(tarball.inputStream().buffered()).use { TarExtractor.readFile(it, CHECKSUMS, MAX_CHECKSUMS_BYTES) }
            ?: return Result.Skipped("$PACKAGE@$version has no checksums.json (Electron 14 and older)")
        val npmSum = checksum(String(json, Charsets.UTF_8), zipName)
            ?: return Result.Skipped("$PACKAGE@$version's checksums.json doesn't list $zipName")
        return if (npmSum == expected.lowercase()) Result.Match
        else Result.Mismatch("npm's checksum of $zipName is $npmSum, GitHub's is $expected")
    }

    /** A version document's `dist`: the tarball's integrity (`sha512-<base64>`) and its signatures (key ID, signature). */
    data class Dist(val integrity: String, val signatures: List<Pair<String, String>>)

    fun dist(document: String): Dist? {
        val dist = JSONObject(document).optJSONObject("dist") ?: return null
        val integrity = dist.optString("integrity").split(WHITESPACE).firstOrNull { it.startsWith("sha512-") } ?: return null
        val array = dist.optJSONArray("signatures")
        val signatures = (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val s = array!!.optJSONObject(i) ?: return@mapNotNull null
            val keyId = s.optString("keyid").ifEmpty { return@mapNotNull null }
            val sig = s.optString("sig").ifEmpty { return@mapNotNull null }
            keyId to sig
        }
        return Dist(integrity, signatures)
    }

    /** npm's registry signature: ECDSA P-256 with SHA-256 over `<name>@<version>:<integrity>`. */
    fun verifies(name: String, version: String, integrity: String, signature: String, key: String): Boolean {
        val sig = fromBase64(signature) ?: return false
        val der = fromBase64(key) ?: return false
        return try {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update("$name@$version:$integrity".toByteArray(Charsets.UTF_8))
                verify(sig)
            }
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    /** Whether [file]'s SHA-512 is [integrity] (`sha512-<base64>`). */
    fun integrityMatches(integrity: String, file: File): Boolean {
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.interrupted()) throw InterruptedException()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return "sha512-" + base64(digest.digest()) == integrity
    }

    /** [file]'s SHA-256 in `checksums.json` (`{"<name>": "<hex>", …}`), lowercase; null if it has none. */
    fun checksum(json: String, file: String): String? = try {
        JSONObject(json).optString(file).lowercase().takeIf { SHA256_HEX.matches(it) }
    } catch (e: JSONException) {
        null
    }

    private val SHA256_HEX = Regex("[0-9a-f]{64}")
}
