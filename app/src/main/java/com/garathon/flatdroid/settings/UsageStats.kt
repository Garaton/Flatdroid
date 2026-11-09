package com.garathon.flatdroid.settings

import com.garathon.flatdroid.flatpak.InstalledApp

/** What the apps' launch counts and run times add up to, for Flatdroid's Overview. */
data class UsageStats(val launches: Int, val usedMillis: Long, val mostUsed: InstalledApp?) {
    companion object {
        fun of(apps: List<InstalledApp>) = UsageStats(
            launches = apps.sumOf { it.launches },
            usedMillis = apps.sumOf { it.usedMillis },
            mostUsed = apps.filter { it.usedMillis > 0 }.maxByOrNull { it.usedMillis },
        )
    }
}

/** A duration split for showing: whole days, hours and minutes (the smaller parts of a long one are dropped by [Duration.largest]). */
data class Duration(val days: Int, val hours: Int, val minutes: Int) {
    /** The two largest non-zero units that matter: days and hours, hours and minutes, or minutes. */
    val largest: Kind get() = when {
        days > 0 -> Kind.DAYS_HOURS
        hours > 0 -> Kind.HOURS_MINUTES
        else -> Kind.MINUTES
    }

    enum class Kind { DAYS_HOURS, HOURS_MINUTES, MINUTES }

    companion object {
        fun of(millis: Long): Duration {
            val minutes = (millis.coerceAtLeast(0) / 60_000).toInt()
            return Duration(minutes / (24 * 60), minutes / 60 % 24, minutes % 60)
        }
    }
}
