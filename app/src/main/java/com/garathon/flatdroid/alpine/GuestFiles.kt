package com.garathon.flatdroid.alpine

/** Contents of the files Flatdroid generates for the guest. Pure, so they can be unit-tested. */
object GuestFiles {
    /** Used when Android reports no usable DNS server. */
    val FALLBACK_DNS = listOf("1.1.1.1", "9.9.9.9")

    /** musl reads at most three nameserver lines. */
    private const val MAX_NAMESERVERS = 3

    /**
     * resolv.conf for [servers] (textual IPv4/IPv6 addresses, as Android reports them). IPv6
     * scope suffixes are dropped and link-local IPv6 servers skipped, since the guest can't name
     * the interface. Falls back to [FALLBACK_DNS] when nothing usable remains.
     */
    fun resolvConf(servers: List<String>): String {
        val usable = servers
            .map { it.substringBefore('%').trim() }
            .filter { it.isNotEmpty() && !it.lowercase().startsWith("fe80:") }
            .distinct()
            .take(MAX_NAMESERVERS)
            .ifEmpty { FALLBACK_DNS }
        return usable.joinToString("") { "nameserver $it\n" }
    }

    fun hosts(): String = "127.0.0.1 localhost\n::1 localhost\n"

    /** A new random machine-id: 32 lowercase hex digits. */
    fun machineId(random: java.util.Random = java.security.SecureRandom()): String =
        (1..16).joinToString("", postfix = "\n") { "%02x".format(random.nextInt(256)) }

    /** passwd for a Flatpak root: root, and "user" with the app's Android UID, at /home/user. */
    fun passwd(uid: Int, gid: Int): String =
        "root:x:0:0:root:/root:/bin/sh\nuser:x:$uid:$gid:user:/home/user:/bin/sh\n"

    fun group(gid: Int): String = "root:x:0:\nuser:x:$gid:\n"

    /**
     * Openbox's whole configuration (a user rc.xml replaces Openbox's defaults, it isn't merged):
     * normal windows maximised with their title bars kept (GTK3/Qt windows need them to close);
     * no minimise button, since there is no taskbar to bring a window back; tapping the empty
     * desktop lists the windows, minimised ones included (apps can still minimise themselves);
     * close and maximise buttons, moving and resizing. Written for every session and bound into
     * Alpine when Openbox starts.
     */
    fun openboxRc(): String {
        fun bind(button: String, event: String, actions: String) =
            "<mousebind button=\"$button\" action=\"$event\">$actions</mousebind>"
        val focus = "<action name=\"Focus\"/><action name=\"Raise\"/>"
        val windowList = "<action name=\"ShowMenu\"><menu>client-list-combined-menu</menu></action>"
        val resize = listOf("Top", "Bottom", "Left", "Right", "TLCorner", "TRCorner", "BLCorner", "BRCorner")
            .joinToString("") { "<context name=\"$it\">${bind("Left", "Drag", "<action name=\"Resize\"/>")}</context>" }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<openbox_config xmlns="http://openbox.org/3.4/rc">
            |<focus><focusNew>yes</focusNew><followMouse>no</followMouse></focus>
            |<placement><policy>Smart</policy><center>yes</center></placement>
            |<theme><titleLayout>NLMC</titleLayout><keepBorder>yes</keepBorder><animateIconify>no</animateIconify></theme>
            |<desktops><number>1</number></desktops>
            |<keyboard>
            |<keybind key="A-F4"><action name="Close"/></keybind>
            |<keybind key="A-Tab"><action name="NextWindow"/></keybind>
            |</keyboard>
            |<mouse>
            |<dragThreshold>8</dragThreshold>
            |<doubleClickTime>500</doubleClickTime>
            |<context name="Frame">${bind("A-Left", "Drag", "<action name=\"Move\"/>")}</context>
            |$resize
            |<context name="Titlebar">${bind("Left", "Press", focus)}${bind("Left", "Drag", "<action name=\"Move\"/>")}${bind("Left", "DoubleClick", "<action name=\"ToggleMaximize\"/>")}</context>
            |<context name="Client">${bind("Left", "Press", focus)}${bind("Middle", "Press", focus)}${bind("Right", "Press", focus)}</context>
            |<context name="Close">${bind("Left", "Click", "<action name=\"Close\"/>")}</context>
            |<context name="Maximize">${bind("Left", "Click", "<action name=\"ToggleMaximize\"/>")}</context>
            |<context name="Root">${bind("Left", "Press", windowList)}${bind("Right", "Press", windowList)}</context>
            |</mouse>
            |<applications><application class="*" type="normal"><maximized>yes</maximized></application></applications>
            |</openbox_config>
            |""".trimMargin()
    }

    /**
     * Flatpak's `languages` value: the device's languages (BCP 47 tags, most preferred first)
     * reduced to language codes, plus English, e.g. "de;en".
     */
    fun flatpakLanguages(languageTags: List<String>): String =
        (languageTags.map { it.substringBefore('-').substringBefore('_').lowercase() } + "en")
            .filter { it.isNotEmpty() && it != "und" }
            .distinct()
            .joinToString(";")

    /** Flatpak's value for "download every translation". */
    const val ALL_LANGUAGES = "*"

    /**
     * Stand-ins for /proc and /proc/sys entries that Android doesn't let apps read, keyed by guest
     * path. The values are plausible rather than real; tools only need them to parse.
     */
    fun fakeProc(cpuCount: Int, kernelRelease: String, kernelVersion: String, uptimeSeconds: Long, bootEpochSeconds: Long): Map<String, String> {
        val cpus = cpuCount.coerceAtLeast(1)
        val idle = uptimeSeconds.coerceAtLeast(1) * 100 // USER_HZ ticks
        val perCpu = "100 0 100 $idle 0 0 0 0 0 0"
        val stat = buildString {
            append("cpu  ${100L * cpus} 0 ${100L * cpus} ${idle * cpus} 0 0 0 0 0 0\n")
            for (i in 0 until cpus) append("cpu$i $perCpu\n")
            append("intr 0\nctxt 0\nbtime $bootEpochSeconds\nprocesses 1\nprocs_running 1\nprocs_blocked 0\nsoftirq 0\n")
        }
        return linkedMapOf(
            "/proc/loadavg" to "0.00 0.00 0.00 1/1 1\n",
            "/proc/stat" to stat,
            "/proc/uptime" to "$uptimeSeconds.00 ${idle * cpus / 100}.00\n",
            "/proc/version" to "Linux version $kernelRelease (android) $kernelVersion\n",
            "/proc/vmstat" to "pgpgin 0\npgpgout 0\npswpin 0\npswpout 0\npgfault 0\npgmajfault 0\n",
            "/proc/sys/kernel/cap_last_cap" to "40\n",
            "/proc/sys/fs/inotify/max_user_watches" to "8192\n",
            "/proc/sys/kernel/overflowuid" to "65534\n",
            "/proc/sys/kernel/overflowgid" to "65534\n",
        )
    }
}
