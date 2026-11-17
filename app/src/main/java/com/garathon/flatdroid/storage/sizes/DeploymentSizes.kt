package com.garathon.flatdroid.storage.sizes

import com.garathon.flatdroid.util.io.writeAtomically
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** What one Flatpak deployment holds, as [StorageSizes.following] counts it. */
class DeploymentSize(
    /** Every entry's own blocks, links not followed, the deployment's directory included ([StorageSizes.of]). */
    val own: Long,
    /** The contents its links into link2symlink's store lead to, each once: inode, and its bytes at the same index. */
    val inodes: LongArray,
    val bytes: LongArray,
) {
    init {
        require(inodes.size == bytes.size)
    }

    /** The deployment alone, each content counted once. */
    val following: Long get() = own + bytes.sum()
}

/**
 * [DeploymentSize]s saved per commit in [dir], so the deployments a Flatpak task didn't touch
 * aren't walked again: a deployment's directory is named by its commit, and its files don't
 * change once it is deployed. What does replace one (a reinstall deploys the same commit at the
 * same path) gives its directory another [fingerprint], so the saved size isn't used for it.
 * A script that adds files later (extra data) [forget]s it.
 *
 * Saved only once a walk has finished, and in one rename: a stop part-way leaves either no file or
 * the last complete one.
 */
class DeploymentSizes(private val dir: File) {
    /**
     * [commit]'s saved size if [fingerprint] matches the one saved with it, else [measure]d now and
     * saved, unless [keep] says no (something began changing the files while they were walked).
     */
    fun get(commit: String, fingerprint: LongArray, keep: () -> Boolean = { true }, measure: () -> DeploymentSize): DeploymentSize {
        require(COMMIT.matches(commit)) { "Not a commit: $commit" }
        val file = File(dir, commit)
        read(file, fingerprint)?.let { return it }
        val size = measure()
        if (keep()) write(file, fingerprint, size)
        return size
    }

    fun forget(commit: String) {
        if (COMMIT.matches(commit)) File(dir, commit).delete()
    }

    /** Deletes the sizes of every commit not in [commits] (deployments that are gone), and any unfinished write. */
    fun removeExcept(commits: Set<String>) {
        for (file in dir.listFiles().orEmpty()) if (file.name !in commits) file.delete()
    }

    private fun read(file: File, fingerprint: LongArray): DeploymentSize? = try {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != MAGIC) return null
            if (LongArray(fingerprint.size) { input.readLong() }.contentEquals(fingerprint).not()) return null
            val own = input.readLong()
            val count = input.readInt()
            if (own < 0 || count < 0 || count > MAX_CONTENTS) return null
            val inodes = LongArray(count)
            val bytes = LongArray(count)
            for (i in 0 until count) {
                inodes[i] = input.readLong()
                bytes[i] = input.readLong()
                if (bytes[i] < 0) return null
            }
            if (input.readInt() != END) return null
            DeploymentSize(own, inodes, bytes)
        }
    } catch (_: Exception) {
        null
    }

    private fun write(file: File, fingerprint: LongArray, size: DeploymentSize) {
        try {
            dir.mkdirs()
            file.writeAtomically(durable = false) { stream ->
                val out = DataOutputStream(BufferedOutputStream(stream))
                out.writeInt(MAGIC)
                fingerprint.forEach(out::writeLong)
                out.writeLong(size.own)
                out.writeInt(size.inodes.size)
                for (i in size.inodes.indices) {
                    out.writeLong(size.inodes[i])
                    out.writeLong(size.bytes[i])
                }
                out.writeInt(END)
                out.flush()
            }
        } catch (_: Exception) {
            file.delete()
        }
    }

    companion object {
        /** A deployment's directory name: its commit's checksum. */
        val COMMIT = Regex("[0-9a-f]{64}")
        private const val MAGIC = 0x46445331 // "FDS1"
        private const val END = 0x454e4421 // "END!"
        /** Far beyond any runtime's file count; a larger count means the file is damaged. */
        private const val MAX_CONTENTS = 10_000_000
    }
}
