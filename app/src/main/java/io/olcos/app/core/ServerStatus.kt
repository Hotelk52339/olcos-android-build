package io.olcos.app.core

import io.olcos.app.model.OlcrtcLink

/**
 * The server's state as the agent's cached status scripts report it, parsed
 * the way the iOS app parses the same outputs (SSHRunner.parseReadiness,
 * parseVPSStats, parseCarrierList, parseXrayStatus).
 */
data class ServerStatus(
    val readiness: Readiness,
    val containerStatus: String,
    val disk: String,
    val ram: String,
    val uptime: String,
    val rooms: List<Room>,
    val xray: List<XrayDeployment>,
    val version: Long,
    /** The server's core: the commit, `+<series>` when a patch series runs on it; null when unknown. */
    val coreIdentity: String? = null,
) {
    enum class Readiness { NO_PODMAN, NO_IMAGE, NO_CONTAINER, RUNNING, STOPPED, UNKNOWN }

    /**
     * Whether [other] reads the same on the page. Only the event counter
     * differs between two fetches of an unchanged server, and it is not shown:
     * a page that assigns an equal reading re-lays-out for nothing.
     */
    fun sameReadingAs(other: ServerStatus?): Boolean = other != null && copy(version = other.version) == other

    /** One protocol or room on the server: a container with its yaml. */
    data class Room(
        val file: String,
        val provider: String,
        val transport: String,
        val room: String,
        val container: String,
        val status: String,
        val isPrimary: Boolean,
    ) {
        val isUp: Boolean get() = status.startsWith("Up")
        val isMissing: Boolean get() = status == "missing"
        /** An extra room of the carrier (`server-<carrier>-<n>.yaml`): n, 2..8; null for the carrier's own row. */
        val lane: Int?
            get() {
                val prefix = "server-$provider-"
                if (provider.isEmpty() || !file.startsWith(prefix) || !file.endsWith(".yaml")) return null
                return file.removePrefix(prefix).removeSuffix(".yaml").toIntOrNull()?.takeIf { it in 2..8 }
            }
        val serviceName: String get() = OlcrtcLink.carrierLabel(provider)
        val transportName: String get() = OlcrtcLink.transportLabel(transport)
        /** The short form of a long room id, for a row. */
        val shortRoom: String get() = if (room.length > 18) room.take(16) + "…" else room
    }

    data class XrayDeployment(val container: String, val status: String) {
        val isUp: Boolean get() = status.startsWith("Up")
    }

    companion object {
        /** The page refetches after an agent event at most this often: a busy server must not repaint it all the time. */
        const val REFRESH_WINDOW_MS = 30_000L

        /** PURE: how long to hold a refetch that an event asked for, given when the last fetch was (0 = none yet). */
        fun holdBeforeRefetch(lastFetchAt: Long, now: Long): Long =
            if (lastFetchAt <= 0L) 0L else (REFRESH_WINDOW_MS - (now - lastFetchAt)).coerceIn(0L, REFRESH_WINDOW_MS)

        fun parse(outputs: Map<String, String>, version: Long): ServerStatus {
            val readiness = outputs["readiness"].orEmpty()
            val fields = HashMap<String, String>()
            for (raw in readiness.lines()) {
                val line = raw.trim()
                val eq = line.indexOf('=')
                if (eq > 0) fields[line.substring(0, eq)] = line.substring(eq + 1)
            }
            val container = fields["CONTAINER"].orEmpty()
            val state = when {
                !readiness.contains("READINESS_END") -> Readiness.UNKNOWN
                fields["PODMAN"] != "yes" -> Readiness.NO_PODMAN
                fields["IMAGE"] != "yes" -> Readiness.NO_IMAGE
                container.isEmpty() -> Readiness.NO_CONTAINER
                container.startsWith("Up") -> Readiness.RUNNING
                else -> Readiness.STOPPED
            }
            return ServerStatus(
                readiness = state,
                containerStatus = container,
                disk = fields["DISK"].orEmpty(),
                ram = fields["RAM"].orEmpty(),
                uptime = fields["UPTIME"].orEmpty().trim(),
                rooms = parseRooms(outputs["carriers"].orEmpty()),
                xray = parseXray(outputs["xray"].orEmpty()),
                version = version,
                coreIdentity = parseCore(outputs["carriers"].orEmpty()),
            )
        }

        /** `OLCRTC_CORE=<40 hex>` and `OLCRTC_CORE_PATCH=<64 hex>` from the carriers output. */
        fun parseCore(output: String): String? {
            val commit = Maintenance.extract(output, "OLCRTC_CORE")?.trim()?.lowercase() ?: return null
            if (commit.length != 40 || !commit.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val series = Maintenance.extract(output, "OLCRTC_CORE_PATCH")?.trim()?.lowercase().orEmpty()
            val recorded = series.length == 64 && series.all { it in '0'..'9' || it in 'a'..'f' }
            return Maintenance.coreIdentity(commit, if (recorded) series else "")
        }

        /** `OLCRTC_CARRIER_BEGIN` … `KEY=VALUE` … `OLCRTC_CARRIER_END` blocks; the primary first, then by container. */
        fun parseRooms(output: String): List<Room> {
            val rooms = mutableListOf<Room>()
            var block: HashMap<String, String>? = null
            for (raw in output.lines()) {
                val line = raw.trim()
                when {
                    line == "OLCRTC_CARRIER_BEGIN" -> block = HashMap()
                    line == "OLCRTC_CARRIER_END" -> {
                        val b = block
                        if (b != null) {
                            val file = b["FILE"].orEmpty()
                            val container = b["CONTAINER"].orEmpty()
                            if (file.isNotEmpty() && container.isNotEmpty()) {
                                rooms += Room(file, b["PROVIDER"].orEmpty(), b["TRANSPORT"].orEmpty(), b["ROOM"].orEmpty(),
                                    container, b["STATUS"].orEmpty(), file == "server.yaml")
                            }
                        }
                        block = null
                    }
                    block != null -> {
                        val eq = line.indexOf('=')
                        if (eq > 0) block[line.substring(0, eq)] = line.substring(eq + 1)
                    }
                }
            }
            return rooms.sortedWith(compareByDescending<Room> { it.isPrimary }.thenBy { it.container })
        }

        /** `XRAY=<name>\t<status>` lines. */
        fun parseXray(output: String): List<XrayDeployment> = output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (!line.startsWith("XRAY=")) return@mapNotNull null
            val fields = line.substring(5).split('\t', limit = 2)
            val name = fields[0]
            if (!name.startsWith("olcos-xray-") || name.length <= 11) return@mapNotNull null
            XrayDeployment(name, fields.getOrElse(1) { "" })
        }
    }
}
