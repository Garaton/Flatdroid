package com.garathon.flatdroid.session.bus

/**
 * What the app asked to hold off (`org.freedesktop.ScreenSaver` `Inhibit`, the portal's
 * `Inhibit`): each hold by its key and the bus connection that took it, which alone may release
 * it. [changed] hears the [State] each time it changes. At most [MAX] holds at a time.
 */
class Inhibitions(private val changed: (State) -> Unit) {
    /** Whether the screen is kept on ([idle]) and the processor awake ([suspend]). */
    data class State(val idle: Boolean = false, val suspend: Boolean = false)

    private class Hold(val owner: String, val flags: UInt)

    private val holds = LinkedHashMap<String, Hold>()
    private var cookies = 0u
    private var state = State()

    /** A new `ScreenSaver` cookie, never 0. */
    @Synchronized
    fun nextCookie(): UInt {
        cookies++
        if (cookies == 0u) cookies++
        return cookies
    }

    /** Holds what [flags] name for [owner] under [key]; false when [MAX] are held, or [key] is another connection's. */
    @Synchronized
    fun add(owner: String, key: String, flags: UInt): Boolean {
        val held = holds[key]
        if (held != null && held.owner != owner) return false
        if (held == null && holds.size >= MAX) return false
        holds[key] = Hold(owner, flags)
        update()
        return true
    }

    /** Whether anything holds what [flags] name. */
    @Synchronized
    fun holding(flags: UInt): Boolean = holds.values.any { it.flags and flags != 0u }

    /** Releases [key], when [owner] took it. */
    @Synchronized
    fun remove(owner: String, key: String): Boolean {
        if (holds[key]?.owner != owner) return false
        holds.remove(key)
        update()
        return true
    }

    /** Releases everything [owner] holds: it left the bus. */
    @Synchronized
    fun removeOwner(owner: String) {
        if (holds.values.removeAll { it.owner == owner }) update()
    }

    /** Releases everything: at the session's end. */
    @Synchronized
    fun clear() {
        holds.clear()
        update()
    }

    private fun update() {
        val next = State(
            idle = holds.values.any { it.flags and IDLE != 0u },
            suspend = holds.values.any { it.flags and SUSPEND != 0u },
        )
        if (next == state) return
        state = next
        changed(next)
    }

    companion object {
        /** The portal's flags: logout 1, user switch 2, suspend 4, idle 8. */
        const val SUSPEND = 4u
        const val IDLE = 8u
        const val MAX = 64
    }
}
