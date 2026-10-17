package io.olcos.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * What an invited device needs to register with a server's agent, as the
 * invite's list carries it in `#agent*` global fields: the address, the
 * certificate the app pins, the API prefix, the one-time pairing code and the
 * role it grants.
 */
data class AgentOffer(
    val host: String,
    val port: Int,
    val certSHA256: String,
    val prefix: String,
    val code: String,
    val role: String,
) {
    val isComplete: Boolean
        get() = host.isNotEmpty() && port in 1..65535 && certSHA256.length == 64 && prefix.isNotEmpty() && code.isNotEmpty()
}

/** A device invite this server publishes: a list of its own, encrypted with a key only the link carries. */
@Serializable
data class DeviceInvite(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** The file name of the encrypted list on the server: 32 lowercase hex digits. */
    val token: String,
    val createdAt: Long,
    val publishedAt: Long? = null,
    /** SHA-256 of the plaintext list last uploaded, so only a changed list goes up again. */
    val publishedDigest: String? = null,
    /** The agent's one-time pairing code and the role it grants; null for connections only. */
    val agentCode: String? = null,
    val agentRole: String? = null,
)

/**
 * A server this device manages through its agent: the pinned endpoint and the
 * registration this device holds there. `deviceID` is null while pairing is
 * still pending (the code is kept for a retry). A server this device set up
 * or adopted over SSH also keeps the SSH login (its secret lives in
 * ServerSecrets), the records it produced and the invites it publishes.
 */
@Serializable
data class ServerRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val certSHA256: String,
    val prefix: String,
    val deviceID: String? = null,
    val role: String = "member",
    /** The invite (without its key) that brought this server, to tell when a live session runs through it. */
    val source: String? = null,
    val pendingCode: String? = null,
    val lastError: String? = null,
    val sshUser: String? = null,
    val sshPort: Int = 22,
    /** The primary olcrtc container (`olcrtc-server-<id>`), which anchors the deploy dir. */
    val baseContainer: String? = null,
    /** The records this server produced: the whole-server one, the primary, the siblings, Xray. */
    val recordIDs: List<String> = emptyList(),
    val invites: List<DeviceInvite> = emptyList(),
    /** The port the busybox share server answers on, once an invite was published. */
    val sharePort: Int? = null,
    /** The installed agent's source version, so a newer app reinstalls it. */
    val agentVersion: String? = null,
    /** The port of the olcos-bond endpoint on the server, once this phone set it up (ServerBond). */
    val bondPort: Int? = null,
    /** The endpoint's source version, so a newer app rebuilds it. */
    val bondVersion: String? = null,
    /** The whole-server connection built from this server's rooms, when there is one. */
    val bundleRecordID: String? = null,
) {
    val isPaired: Boolean get() = deviceID != null
    val isOwner: Boolean get() = isPaired && role == "owner"
    val hasSSH: Boolean get() = !sshUser.isNullOrEmpty()
    val endpointKey: String get() = "$host:$port"
}
