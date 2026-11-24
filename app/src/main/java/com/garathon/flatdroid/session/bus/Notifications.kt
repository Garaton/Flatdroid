package com.garathon.flatdroid.session.bus

import java.io.FileDescriptor
import java.io.FileInputStream

/**
 * The app's notifications, from `org.freedesktop.Notifications` (apps whose metadata may talk to
 * it) and the Notification portal (any app, as in Flatpak), each shown through [shower] as one
 * Android notification under its own [AppNotification.key]. Tapping one sends the app its default
 * action, a button its action; Android's dismissal tells `org.freedesktop.Notifications` apps
 * it was closed. At most [MAX] at a time.
 */
class Notifications(private val shower: Shower) {
    /** What shows them on Android. */
    interface Shower {
        fun show(notification: AppNotification)
        fun remove(key: Int)
    }

    /** One notification as Android shows it. */
    data class AppNotification(
        val key: Int,
        val title: String,
        val body: String,
        /** Low urgency or priority: shown without sound. */
        val quiet: Boolean,
        /** Its buttons, at most [MAX_BUTTONS]. */
        val buttons: List<Button>,
        /** A tap has an action to send (else it only opens the app). */
        val hasDefault: Boolean,
        val image: Image?,
        /** Removed after this long; null when it stays. */
        val timeoutMillis: Long?,
    )

    data class Button(val label: String, val action: String)

    /** A picture for the notification; the app's icon stands in without one. */
    sealed interface Image {
        /** Raw pixels as `image-data` gives them. */
        class Pixels(val width: Int, val height: Int, val rowStride: Int, val alpha: Boolean, val channels: Int, val data: ByteArray) : Image
        /** A PNG, JPEG or other encoded picture. */
        class Encoded(val bytes: ByteArray) : Image
        /** A file in the app's root. */
        data class Path(val guestPath: String) : Image
    }

    /** Where a shown notification came from, to answer the right interface. */
    private sealed interface Source {
        val owner: String
        data class Freedesktop(override val owner: String, val id: UInt, val resident: Boolean) : Source
        data class FromPortal(override val owner: String, val id: String, val default: Pair<String, Variant?>?, val targets: Map<String, Variant?>, val appId: String?) : Source
    }

    private val shown = LinkedHashMap<Int, Source>()
    private var nextId = 0u
    private var nextKey = 0

    /** `Notify`: shows or replaces [replacesId]'s, and answers its id; null when [MAX] are shown. */
    @Synchronized
    fun notify(
        owner: String, replacesId: UInt, appIcon: String, summary: String, body: String,
        actions: List<String>, hints: Map<*, *>, expireTimeout: Int,
    ): UInt? {
        val previous = shown.entries.firstOrNull { (_, s) -> s is Source.Freedesktop && s.owner == owner && s.id == replacesId && replacesId != 0u }
        val key = previous?.key ?: newKey() ?: return null
        val id = (previous?.value as? Source.Freedesktop)?.id ?: newId()
        val pairs = actions.chunked(2).filter { it.size == 2 }
        val urgency = ((hints["urgency"] as? Variant)?.value as? UByte)?.toInt() ?: 1
        val image = pixels(hints["image-data"] ?: hints["image_data"])
            ?: path(hints["image-path"] ?: hints["image_path"])
            ?: appIcon.takeIf { it.startsWith("/") || it.startsWith("file:") }?.let { Image.Path(it.removePrefix("file://")) }
            ?: pixels(hints["icon_data"])
        shown[key] = Source.Freedesktop(owner, id, resident = (hints["resident"] as? Variant)?.value == true)
        shower.show(
            AppNotification(
                key, text(summary), text(body), quiet = urgency == 0,
                buttons = pairs.filter { it[0] != DEFAULT }.take(MAX_BUTTONS).map { Button(text(it[1]), it[0]) },
                hasDefault = pairs.any { it[0] == DEFAULT },
                image = image,
                timeoutMillis = expireTimeout.takeIf { it > 0 }?.toLong(),
            ),
        )
        return id
    }

    /** `CloseNotification`: removed, and `NotificationClosed` (reason 3, closed by a call) for it. */
    @Synchronized
    fun close(owner: String, id: UInt): List<Message> {
        val key = shown.entries.firstOrNull { (_, s) -> s is Source.Freedesktop && s.owner == owner && s.id == id }?.key ?: return emptyList()
        shown.remove(key)
        shower.remove(key)
        return listOf(closed(owner, id, CLOSED_BY_CALL))
    }

    /** The portal's `AddNotification` ([appId] set for the backend's): shows or replaces [id]'s; false when [MAX] are shown. */
    @Synchronized
    fun add(owner: String, id: String, options: Map<*, *>, fds: List<FileDescriptor>, appId: String? = null): Boolean {
        val previous = shown.entries.firstOrNull { (_, s) -> s is Source.FromPortal && s.owner == owner && s.id == id }
        val key = previous?.key ?: newKey() ?: return false
        fun string(name: String) = (options[name] as? Variant)?.value as? String
        val buttons = ((options["buttons"] as? Variant)?.value as? List<*>).orEmpty().mapNotNull { button ->
            val map = button as? Map<*, *> ?: return@mapNotNull null
            val label = (map["label"] as? Variant)?.value as? String ?: return@mapNotNull null
            val action = (map["action"] as? Variant)?.value as? String ?: return@mapNotNull null
            Triple(label, action, (map["target"] as? Variant)?.value as? Variant)
        }.take(MAX_BUTTONS)
        val default = string("default-action")?.let { it to ((options["default-action-target"] as? Variant)?.value as? Variant) }
        val priority = string("priority")
        shown[key] = Source.FromPortal(owner, id, default, buttons.associate { it.second to it.third }, appId)
        shower.show(
            AppNotification(
                key,
                text(string("title").orEmpty()),
                text(string("body") ?: string("markup-body")?.let(::withoutMarkup).orEmpty()),
                quiet = priority == "low",
                buttons = buttons.map { Button(text(it.first), it.second) },
                hasDefault = default != null,
                image = portalIcon((options["icon"] as? Variant)?.value, fds),
                timeoutMillis = null,
            ),
        )
        return true
    }

    /** The portal's `RemoveNotification`. */
    @Synchronized
    fun removeFromPortal(owner: String, id: String) {
        val key = shown.entries.firstOrNull { (_, s) -> s is Source.FromPortal && s.owner == owner && s.id == id }?.key ?: return
        shown.remove(key)
        shower.remove(key)
    }

    /**
     * The user tapped [key]'s notification ([action] null) or one of its buttons: the signals that
     * tell the app; the notification goes (unless resident).
     */
    @Synchronized
    fun activated(key: Int, action: String?): List<Message> {
        val source = shown[key] ?: return emptyList()
        return when (source) {
            is Source.Freedesktop -> {
                val signals = listOf(
                    Message.signal(PATH, IFACE, "ActionInvoked", "us", source.id, action ?: DEFAULT).copy(destination = source.owner),
                )
                if (source.resident) signals
                else {
                    remove(key)
                    signals + closed(source.owner, source.id, DISMISSED)
                }
            }
            is Source.FromPortal -> {
                remove(key)
                val (name, target) = if (action == null) source.default ?: return emptyList() else action to source.targets[action]
                val parameter = listOfNotNull(target)
                // To the caller alone (the app, or the real portal for the backend's), as every signal here: the proxy lets those through.
                listOf(
                    if (source.appId == null) Message.signal(Portal.PATH, PORTAL_IFACE, "ActionInvoked", "ssav", source.id, name, parameter)
                    else Message.signal(Portal.PATH, IMPL_IFACE, "ActionInvoked", "sssav", source.appId, source.id, name, parameter),
                ).map { it.copy(destination = source.owner) }
            }
        }
    }

    /** The user swiped [key]'s notification away. */
    @Synchronized
    fun dismissed(key: Int): List<Message> {
        val source = shown.remove(key) ?: return emptyList()
        return if (source is Source.Freedesktop) listOf(closed(source.owner, source.id, DISMISSED)) else emptyList()
    }

    /** Removes everything: at the session's end. */
    @Synchronized
    fun clear() {
        for (key in shown.keys) shower.remove(key)
        shown.clear()
    }

    /** Android takes a tapped notification away itself, not one whose button was pressed. */
    private fun remove(key: Int) {
        shown.remove(key)
        shower.remove(key)
    }

    private fun newKey(): Int? = if (shown.size >= MAX) null else ++nextKey
    private fun newId(): UInt {
        nextId++
        if (nextId == 0u) nextId++
        return nextId
    }

    private fun closed(owner: String, id: UInt, reason: UInt) =
        Message.signal(PATH, IFACE, "NotificationClosed", "uu", id, reason).copy(destination = owner)

    /** `image-data`: `(iiibiiay)` width, height, row stride, alpha, bits per sample, channels, pixels. */
    private fun pixels(value: Any?): Image? {
        val struct = (value as? Variant)?.value as? Struct ?: return null
        val v = struct.values
        if (v.size != 7) return null
        val width = v[0] as? Int ?: return null
        val height = v[1] as? Int ?: return null
        val stride = v[2] as? Int ?: return null
        val alpha = v[3] as? Boolean ?: return null
        val bits = v[4] as? Int ?: return null
        val channels = v[5] as? Int ?: return null
        val data = (v[6] as? List<*>)?.let(::bytes) ?: return null
        if (bits != 8 || channels != (if (alpha) 4 else 3) || width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        if (stride < width * channels || data.size < stride.toLong() * (height - 1) + width * channels) return null
        return Image.Pixels(width, height, stride, alpha, channels, data)
    }

    private fun path(value: Any?): Image? {
        val path = (value as? Variant)?.value as? String ?: return null
        val guest = if (path.startsWith("file:")) Portal.guestPath(path) else path.takeIf { it.startsWith("/") }
        return guest?.let { Image.Path(it) }
    }

    /** The portal's `icon`: `("bytes", ay)` or `("file-descriptor", h)`; a themed one gives none (the app's icon stands in). */
    private fun portalIcon(value: Any?, fds: List<FileDescriptor>): Image? {
        val struct = value as? Struct ?: return null
        val kind = struct.values.getOrNull(0) as? String
        val content = (struct.values.getOrNull(1) as? Variant)?.value
        return when (kind) {
            "bytes" -> (content as? List<*>)?.let(::bytes)?.let { Image.Encoded(it) }
            "file-descriptor" -> (content as? UnixFd)?.let { fds.getOrNull(it.index) }?.let { fd ->
                runCatching { FileInputStream(fd).readNBytesCompat(MAX_IMAGE_BYTES + 1) }.getOrNull()
                    ?.takeIf { it.size <= MAX_IMAGE_BYTES }?.let { Image.Encoded(it) }
            }
            else -> null
        }
    }

    private fun bytes(list: List<*>): ByteArray? {
        if (list.size > MAX_IMAGE_BYTES) return null
        val out = ByteArray(list.size)
        for ((i, b) in list.withIndex()) out[i] = (b as? UByte)?.toByte() ?: return null
        return out
    }

    /** At most [MAX_TEXT] characters. */
    private fun text(value: String) = if (value.length > MAX_TEXT) value.take(MAX_TEXT) + "…" else value

    companion object {
        const val NAME = "org.freedesktop.Notifications"
        const val IFACE = NAME
        const val PATH = "/org/freedesktop/Notifications"
        const val PORTAL_IFACE = "org.freedesktop.portal.Notification"
        const val IMPL_IFACE = "org.freedesktop.impl.portal.Notification"
        /** The action a tap sends. */
        const val DEFAULT = "default"
        const val MAX = 32
        const val MAX_BUTTONS = 3
        private const val MAX_TEXT = 2000
        private const val MAX_SIDE = 1024
        private const val MAX_IMAGE_BYTES = 4 shl 20
        /** `NotificationClosed` reasons: dismissed by the user 2, closed by `CloseNotification` 3. */
        const val DISMISSED = 2u
        const val CLOSED_BY_CALL = 3u

        /** What `GetCapabilities` answers: buttons, a body (plain text), pictures, and notifications that stay after an action. */
        val CAPABILITIES = listOf("actions", "body", "icon-static", "persistence")

        /** [markup]'s text: tags dropped, the few entities `body-markup` allows decoded. */
        fun withoutMarkup(markup: String): String =
            markup.replace(Regex("<[^>]*>"), "").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}

/** Up to [max] bytes ([java.io.InputStream.readNBytes] needs Android 13). */
private fun FileInputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
