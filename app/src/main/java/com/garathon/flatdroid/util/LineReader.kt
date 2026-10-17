package com.garathon.flatdroid.util

import java.io.Reader

/**
 * Calls [action] for each line of this reader, as BufferedReader.readLine() splits them (at
 * `\n`, `\r` or `\r\n`), but a line longer than [maxLength] is handed over in pieces of
 * [maxLength]: output without line breaks (a binary `cat`, a hostile program) then can't grow
 * Flatdroid's memory without bound.
 */
fun Reader.forEachLineLimited(maxLength: Int, action: (String) -> Unit) {
    require(maxLength > 0)
    val line = StringBuilder()
    val buf = CharArray(8192)
    var afterCr = false
    while (true) {
        val n = read(buf)
        if (n < 0) break
        for (i in 0 until n) {
            val c = buf[i]
            if (afterCr) {
                afterCr = false
                if (c == '\n') continue
            }
            when (c) {
                '\n', '\r' -> {
                    action(line.toString())
                    line.setLength(0)
                    afterCr = c == '\r'
                }
                else -> {
                    line.append(c)
                    if (line.length >= maxLength) {
                        action(line.toString())
                        line.setLength(0)
                    }
                }
            }
        }
    }
    if (line.isNotEmpty()) action(line.toString())
}
