package com.garathon.flatdroid.proot

/**
 * Flatdroid's V4L2 devices (`native/fdv4l2.c`, shipped as [LIBRARY]), preloaded into apps whose
 * camera runs in device mode: each Android camera is a `/dev/video*` device in the app's root, a
 * pipe of frames the library answers V4L2's calls on, with a media controller device
 * (`/dev/media*`) for libcamera and a control pipe through which the library says which format the
 * app chose. It reads the cameras from [CAMERAS_PATH]. Apps that find cameras through udev or
 * libcamera look in `/sys` and `/run/udev`: [tree] lays out what a USB camera has there, for these
 * cameras only. All of it is written for each launch, and the library is bound read-only from where
 * the app can't change it (nativeLibraryDir).
 */
object Fdv4l2 {
    const val LIBRARY = "libfdv4l2.so"

    /** Where it is bound in a Flatpak's root, beside fdsec ([Fdsec.APP_PATH]). */
    const val APP_PATH = "/.flatdroid/fdv4l2.so"
    const val CAMERAS_PATH = "/.flatdroid/cameras"

    /** The character devices' major numbers: V4L2's, and one for the media devices (theirs is given out at boot). */
    const val VIDEO_MAJOR = 81
    const val MEDIA_MAJOR = 238

    /** The devices of the camera at [index] (in Android's order). */
    fun devicePath(index: Int) = "/dev/video$index"
    fun mediaPath(index: Int) = "/dev/media$index"
    fun controlPath(index: Int) = "/.flatdroid/video$index.format"

    /** The frame formats, by their name in the list and the control pipe, and a frame's Content-Type. */
    enum class Format(val id: String, val contentType: String) {
        MJPEG("mjpeg", "image/jpeg"),
        NV12("nv12", "video/nv12");

        companion object {
            fun of(id: String): Format? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * The formats offered, in order: NV12 first (no JPEG to encode or decode: fewer processor
     * time and more frames, for apps that take the first), then Motion-JPEG for apps that take
     * only that. Apps with their own preference pick theirs.
     */
    val DEFAULT_FORMATS = listOf(Format.NV12, Format.MJPEG)

    /**
     * A camera as the library announces it: frames of [width]×[height] at [rate] per second.
     * [front]: it faces the user ([tree] tells libcamera so).
     */
    data class Device(val index: Int, val width: Int, val height: Int, val rate: Int, val name: String, val front: Boolean = false)

    /**
     * The cameras list: one line per device,
     * "<video> <media> <control> <width> <height> <rate> <formats> <name>". Names keep only
     * letters, digits and spaces, at most 31 bytes of UTF-8 (the library's limit), cut between characters.
     */
    fun camerasList(devices: List<Device>, formats: List<Format> = DEFAULT_FORMATS): String = devices.joinToString("") { device ->
        require(device.index >= 0 && device.width > 0 && device.height > 0 && device.rate > 0 && formats.isNotEmpty())
        val i = device.index
        "${devicePath(i)} ${mediaPath(i)} ${controlPath(i)} ${device.width} ${device.height} ${device.rate} " +
            "${formats.distinct().joinToString(",") { it.id }} ${plainName(device.name)}\n"
    }

    /** The format the library last reported on a control pipe ([read]: its text since last time), or null. */
    fun chosenFormat(read: String): Format? = read.lines().mapNotNull { Format.of(it.trim()) }.lastOrNull()

    /**
     * Whether the app streams from the device, as the library last reported on a control pipe
     * ([read]: its text since last time): "on" at stream-on, "off" at stream-off or when the device
     * is closed while streaming; null when it said neither.
     */
    fun streaming(read: String): Boolean? = read.lines().map { it.trim() }.lastOrNull { it == ON || it == OFF }?.let { it == ON }

    private const val ON = "on"
    private const val OFF = "off"

    /** LD_PRELOAD with the library first and the app's own [current] value, if any, after it. */
    fun preload(current: String?): String = listOfNotNull(APP_PATH, current?.trim()?.takeIf { it.isNotEmpty() }).joinToString(":")

    /** An entry of [tree]: a directory, a file with its text, or a symbolic link to a path in the app's root. */
    sealed class Entry {
        data object Dir : Entry()
        data class Text(val text: String) : Entry()
        data class Link(val target: String) : Entry()
    }

    /**
     * The folders of [tree], each bound at its path in the app's root. `/sys/class` is one of
     * them: udev finds devices by listing it, and Android refuses apps a listing of it (of nearly
     * all of `/sys`). Android's own is then bound at [ANDROID_CLASSES], which the classes' links
     * in [tree] lead to, so reading a class's files by their path still works; its own links
     * (`../../devices/...`) still land in `/sys/devices` from there.
     */
    val TREE_BINDS = listOf(
        "devices" to "/sys/devices/flatdroid",
        "class" to "/sys/class",
        "bus-media" to "/sys/bus/media",
        "udev" to "/run/udev",
    )

    /** Where Android's `/sys/class` is bound, beside the cameras' own, when the app has the whole of `/sys`. */
    const val ANDROID_CLASSES = "/sys/class-android"

    /**
     * Device classes Linux kernels name, for the links to Android's in the cameras' `/sys/class`:
     * which are there can't be listed, only looked up one by one.
     */
    val KNOWN_CLASSES = listOf(
        "android_usb", "ata_device", "ata_link", "ata_port", "backlight", "bdi", "block", "bluetooth", "bsg",
        "devcoredump", "devfreq", "devfreq-event", "devlink", "dma", "dma_heap", "drm", "extcon", "firmware",
        "gpio", "graphics", "hidraw", "hwmon", "i2c-adapter", "i2c-dev", "ieee80211", "input", "ion", "iommu",
        "kgsl", "leds", "mdio_bus", "mem", "misc", "mmc_host", "mtd", "net", "nvme", "pci_bus", "phy",
        "power_supply", "powercap", "ptp", "pwm", "regulator", "remoteproc", "rfkill", "rpmsg", "rtc",
        "scsi_device", "scsi_disk", "scsi_host", "sound", "spi_master", "spidev", "switch", "thermal", "tpm",
        "tty", "typec", "udc", "usb_role", "usbmisc", "vc", "virtio-ports", "vtconsole", "wakeup", "watchdog",
    )

    /**
     * The app's environment in device mode: udev (systemd's) takes only devices on a sysfs
     * filesystem, which [tree] isn't; this is udev's own switch for that check.
     */
    val ENVIRONMENT = mapOf("SYSTEMD_DEVICE_VERIFY_SYSFS" to "0")

    /**
     * Also bound: a folder for each camera's video and media device at its
     * `/sys/dev/char/<major>:<minor>`, with what the device's own folder has (their own copies:
     * proot's binds can't take a host path with a colon, which the USB interface's name has).
     */
    fun charBinds(devices: List<Device>): List<Pair<String, String>> = devices.flatMap { device ->
        val i = device.index
        listOf("char/video$i" to "/sys/dev/char/$VIDEO_MAJOR:$i", "char/media$i" to "/sys/dev/char/$MEDIA_MAJOR:$i")
    }

    /**
     * What a USB camera has in `/sys` and udev's database, for [devices]: relative paths under
     * one folder (its parts bound as [TREE_BINDS] and [charBinds] say) to their entries. Each camera
     * is a USB device under `/sys/devices/flatdroid` with a firmware node (libcamera names cameras
     * by it), its interface holding the video node and the media device; the USB device's
     * `removable` is where libcamera reads which way a USB camera faces: "fixed" (built in) for a
     * front camera, which apps then mirror, "removable" (external) for the rest, as libcamera
     * has no way to say back; `/sys/class/video4linux`
     * and `/sys/bus/media` link to them; udev's database marks the video nodes initialized, with
     * the properties GStreamer reads.
     */
    fun tree(devices: List<Device>, androidClasses: List<String> = emptyList()): Map<String, Entry> {
        val entries = linkedMapOf<String, Entry>(
            "devices" to Entry.Dir,
            "devices/firmware_node/path" to Entry.Text("\\_SB_.FLATDROID\n"),
            "class/video4linux" to Entry.Dir,
            "bus-media/devices" to Entry.Dir,
            "udev/data" to Entry.Dir,
        )
        for (name in androidClasses) {
            if (name != "video4linux" && CLASS_NAME.matches(name)) entries["class/$name"] = Entry.Link("../${ANDROID_CLASSES.substringAfterLast('/')}/$name")
        }
        for (device in devices) {
            val i = device.index
            val name = plainName(device.name)
            val usb = "devices/usb1/1-${i + 1}"
            val iface = "devices/${interfaceDir(i)}"
            val ifaceGuest = "$SYS_DEVICES/${interfaceDir(i)}"
            entries["$usb/idVendor"] = Entry.Text("$VENDOR\n")
            entries["$usb/idProduct"] = Entry.Text("%04x\n".format(i + 1))
            entries["$usb/product"] = Entry.Text("$name\n")
            entries["$usb/removable"] = Entry.Text(if (device.front) "fixed\n" else "removable\n")
            for ((node, major, subsystem) in listOf(
                Triple("video4linux/video$i", VIDEO_MAJOR, "/sys/class/video4linux"),
                Triple("media$i", MEDIA_MAJOR, "/sys/bus/media"),
            )) {
                val devName = node.substringAfterLast('/')
                for (folder in listOf("$iface/$node", "char/$devName")) {
                    entries["$folder/uevent"] = Entry.Text("MAJOR=$major\nMINOR=$i\nDEVNAME=$devName\n")
                    entries["$folder/dev"] = Entry.Text("$major:$i\n")
                    entries["$folder/subsystem"] = Entry.Link(subsystem)
                    entries["$folder/device"] = Entry.Link(ifaceGuest)
                }
            }
            entries["$iface/video4linux/video$i/name"] = Entry.Text("$name\n")
            entries["$iface/video4linux/video$i/index"] = Entry.Text("0\n")
            entries["class/video4linux/video$i"] = Entry.Link("$ifaceGuest/video4linux/video$i")
            entries["bus-media/devices/media$i"] = Entry.Link("$ifaceGuest/media$i")
            entries["udev/data/c$VIDEO_MAJOR:$i"] = Entry.Text(
                "I:1\nE:ID_V4L_VERSION=2\nE:ID_V4L_PRODUCT=$name\nE:ID_V4L_CAPABILITIES=:capture:\n" +
                    "E:ID_PATH=platform-flatdroid-usb-0:${i + 1}:1.0\nG:uaccess\nQ:uaccess\n",
            )
        }
        return entries
    }

    private const val SYS_DEVICES = "/sys/devices/flatdroid"
    private val CLASS_NAME = Regex("[a-z0-9_-]+")
    /** Shows as the cameras' USB vendor; no real vendor's number. */
    private const val VENDOR = "f1d0"

    private fun interfaceDir(index: Int) = "usb1/1-${index + 1}/1-${index + 1}:1.0"

    private fun plainName(name: String): String {
        var plain = name.filter { it.isLetterOrDigit() || it == ' ' }.trim()
        while (plain.toByteArray().size > NAME_MAX) plain = plain.dropLast(1)
        return plain.trim().ifEmpty { "Camera" }
    }

    private const val NAME_MAX = 31
}
