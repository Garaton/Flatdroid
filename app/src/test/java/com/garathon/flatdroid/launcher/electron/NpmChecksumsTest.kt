package com.garathon.flatdroid.launcher.electron

import com.garathon.flatdroid.setup.rootfs.Tar
import com.garathon.flatdroid.util.base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.GZIPOutputStream

class NpmChecksumsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** electron@30.0.0's `dist` as npm serves it, signed with npm's first key. */
    private val v30 = """
        {"name":"electron","version":"30.0.0","dist":{
          "shasum":"6b72a27dcc46759fac5f12e147ef64554e596391",
          "tarball":"https://registry.npmjs.org/electron/-/electron-30.0.0.tgz",
          "integrity":"sha512-GRwKphq/TUhSlb44OwSckXKl50f5OR/pm9MvF3rBLyqcxwfu7L11xejrZ0hDea1eKyCkzGd4B+cIqaQiDguPEA==",
          "signatures":[{"sig":"MEUCIGykh1Qllby3c+09bOHzSzsZZVNSUipCrRN8SHLUUivYAiEAwm3DVDvYmFVtZAqHhzrUeJ+zMzqPQQvLLDArsipw7HE=",
            "keyid":"SHA256:jl3bwswu80PjjokCgh0o2w5c2U4LhQAE57gj9cz1kzA"}]}}
    """.trimIndent()

    @Test
    fun npmsOwnSignatureVerifiesWithThePinnedKey() {
        val dist = NpmChecksums.dist(v30)!!
        val (keyId, sig) = dist.signatures.single()
        val key = NpmChecksums.KEYS.getValue(keyId)
        assertTrue(NpmChecksums.verifies("electron", "30.0.0", dist.integrity, sig, key))
        assertFalse(NpmChecksums.verifies("electron", "30.0.1", dist.integrity, sig, key))
        assertFalse(NpmChecksums.verifies("electron", "30.0.0", dist.integrity.replace('G', 'H'), sig, key))
        assertFalse(NpmChecksums.verifies("electron", "30.0.0", dist.integrity, sig, NpmChecksums.KEYS.values.last()))
        assertFalse(NpmChecksums.verifies("electron", "30.0.0", dist.integrity, "not base64", key))
    }

    @Test
    fun checksumsJsonGivesAFilesHash() {
        val json = """{"electron-v30.0.0-linux-arm64.zip": "CA4FA1E20D3E24F8BEB00E0669D6293368ED454B4429C95A298E96C8D6BFFCAA", "short.zip": "abc"}"""
        assertEquals("ca4fa1e20d3e24f8beb00e0669d6293368ed454b4429c95a298e96c8d6bffcaa", NpmChecksums.checksum(json, "electron-v30.0.0-linux-arm64.zip"))
        assertNull(NpmChecksums.checksum(json, "short.zip"))
        assertNull(NpmChecksums.checksum(json, "missing.zip"))
        assertNull(NpmChecksums.checksum("not json", "short.zip"))
    }

    // The whole check, with a key made here standing in for npm's.
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val keys = mapOf("SHA256:test" to base64(pair.public.encoded))
    private val zipName = "electron-v30.0.0-linux-arm64.zip"
    private val github = "ca4fa1e20d3e24f8beb00e0669d6293368ed454b4429c95a298e96c8d6bffcaa"
    private val served = mutableMapOf<String, ByteArray>()

    private fun tarball(files: Map<String, String>): ByteArray {
        val tar = Tar().apply { for ((name, text) in files) entry(name, '0', data = text.toByteArray()) }
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tar.bytes()) } }.toByteArray()
    }

    private fun serve(tgz: ByteArray, signedTgz: ByteArray = tgz, keyId: String = "SHA256:test") {
        val integrity = "sha512-" + base64(MessageDigest.getInstance("SHA-512").digest(signedTgz))
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update("electron@30.0.0:$integrity".toByteArray())
            base64(sign())
        }
        served[NpmChecksums.versionUrl("30.0.0")] =
            """{"dist":{"integrity":"$integrity","signatures":[{"keyid":"$keyId","sig":"$sig"}]}}""".toByteArray()
        served[NpmChecksums.tarballUrl("30.0.0")] = tgz
    }

    private fun check(expected: String = github) = NpmChecksums.check("30.0.0", zipName, expected, tmp.newFolder(), { url, dest, _ ->
        dest.writeBytes(served[url] ?: throw IOException("404 $url"))
    }, keys)

    private val withChecksums = tarball(mapOf("package/package.json" to "{}", "package/checksums.json" to """{"$zipName": "$github"}"""))

    @Test
    fun aSignedMatchingChecksumMatches() {
        serve(withChecksums)
        assertEquals(NpmChecksums.Result.Match, check())
        assertEquals(NpmChecksums.Result.Match, check(github.uppercase()))
    }

    @Test
    fun aDifferentChecksumOrAnUnsignedPackageIsAMismatch() {
        serve(withChecksums)
        assertTrue(check("0".repeat(64)) is NpmChecksums.Result.Mismatch)
        // A package other than the one npm signed.
        serve(withChecksums, signedTgz = tarball(mapOf("package/checksums.json" to "{}")))
        assertTrue(check() is NpmChecksums.Result.Mismatch)
        // A signature that doesn't verify.
        serve(withChecksums)
        served[NpmChecksums.versionUrl("30.0.0")] = String(served.getValue(NpmChecksums.versionUrl("30.0.0")))
            .replace("\"sig\":\"", "\"sig\":\"AAAA").toByteArray()
        assertTrue(check() is NpmChecksums.Result.Mismatch)
    }

    @Test
    fun noAnswerFromNpmLeavesGithubsAlone() {
        assertTrue(check() is NpmChecksums.Result.Skipped)
        serve(withChecksums, keyId = "SHA256:unknown")
        assertTrue(check() is NpmChecksums.Result.Skipped)
        // Electron 14 and older ship no checksums.json.
        serve(tarball(mapOf("package/package.json" to "{}")))
        assertTrue(check() is NpmChecksums.Result.Skipped)
        serve(tarball(mapOf("package/checksums.json" to """{"other.zip": "$github"}""")))
        assertTrue(check() is NpmChecksums.Result.Skipped)
        served[NpmChecksums.versionUrl("30.0.0")] = "<html>".toByteArray()
        assertTrue(check() is NpmChecksums.Result.Skipped)
    }
}
