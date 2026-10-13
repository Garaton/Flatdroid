package com.garathon.flatdroid

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garathon.flatdroid.proot.ProotCommand
import com.garathon.flatdroid.proot.ProotPaths
import com.garathon.flatdroid.proot.ProotRunner
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.DownloadRootfsSource
import com.garathon.flatdroid.setup.RootfsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Temporary check screen: extract Alpine, then run commands in it through proot. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun runTest() {
        if (_running.value) return
        _running.value = true
        _output.value = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                test()
            } catch (e: Exception) {
                log("ERROR: $e")
            } finally {
                _running.value = false
            }
        }
    }

    private fun test() {
        val app = getApplication<Application>()
        val libDir = File(app.applicationInfo.nativeLibraryDir)
        // nativeLibraryDir ends in lib/arm64 or lib/x86_64: the ABI that was actually installed.
        val arch = ARCH_BY_LIB_DIR[libDir.name] ?: error("Unsupported ABI directory: $libDir")
        val sha256 = when (arch) {
            "aarch64" -> BuildConfig.ALPINE_SHA256_AARCH64
            else -> BuildConfig.ALPINE_SHA256_X86_64
        }
        var lastMb = -1L
        val source = DownloadRootfsSource(
            BuildConfig.ALPINE_VERSION, BuildConfig.ALPINE_BRANCH, arch, sha256,
            cacheFile = File(app.cacheDir, "alpine-minirootfs-${BuildConfig.ALPINE_VERSION}-$arch.tar.gz"),
        ) { bytes, total ->
            val mb = bytes shr 20
            if (mb != lastMb) {
                lastMb = mb
                log("  $mb / ${if (total > 0) total shr 20 else "?"} MiB")
            }
        }
        val installer = RootfsInstaller(File(app.filesDir, "alpine"), app.cacheDir, AndroidTarFs)

        val installed = installer.installedVersion()
        if (installed != null) {
            log("Alpine $installed ($arch) already extracted")
        } else {
            log("Downloading and extracting Alpine ${source.version} ($arch)\n  ${source.url}")
            val start = System.nanoTime()
            installer.install(source)
            log("Done in ${(System.nanoTime() - start) / 1_000_000} ms")
        }

        val tmp = File(app.cacheDir, "proot-tmp").apply { mkdirs() }
        val paths = ProotPaths(
            proot = File(libDir, "libproot.so").path,
            loader = File(libDir, "libproot-loader.so").path,
            tmpDir = tmp.path,
            rootfs = installer.rootfs.path,
        )
        for (command in TEST_COMMANDS) {
            log("\n$ ${command.joinToString(" ")}")
            val result = ProotRunner.run(ProotCommand.alpine(paths, command), app.filesDir)
            log(result.output.trimEnd())
            log(if (result.timedOut) "(timed out)" else "(exit ${result.exitCode})")
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _output.update { if (it.isEmpty()) line else "$it\n$line" }
    }

    private companion object {
        const val TAG = "Flatdroid"
        val ARCH_BY_LIB_DIR = mapOf("arm64" to "aarch64", "x86_64" to "x86_64")
        val TEST_COMMANDS = listOf(listOf("/bin/echo", "hi"), listOf("/sbin/apk", "--version"))
    }
}
