package com.bluenet.node

import com.bluenet.core.PeerIds

/** A peer as shown to the user. */
data class Peer(
    val id: String,
    val name: String,
    val isSharingInternet: Boolean,
    /** Signal strength if the peer is in direct BLE range. */
    val rssi: Int?,
    /** Mesh distance from its last signed announcement, if one was received. */
    val hops: Int?,
    val linked: Boolean,
) {
    val nearby: Boolean get() = rssi != null

    /** Tunnels are single-hop, so only a sharer in direct range can carry our traffic. */
    val canProvideInternet: Boolean get() = nearby && isSharingInternet
}

/** A peer seen in direct BLE range. */
data class NearbyPeer(val id: String, val name: String, val isSharingInternet: Boolean, val rssi: Int)

/** A peer known from a signed mesh announcement. */
data class Contact(val id: String, val name: String, val isSharingInternet: Boolean, val hops: Int, val lastSeenMs: Long)

object PeerDirectory {
    const val CONTACT_TIMEOUT_MS = 3 * 60 * 1000L

    fun merge(nearby: Collection<NearbyPeer>, contacts: Collection<Contact>, linked: Set<String>): List<Peer> {
        val byId = nearby.associateBy { it.id }
        val contactsById = contacts.associateBy { it.id }
        return (byId.keys + contactsById.keys + linked).map { id ->
            val sighting = byId[id]
            val contact = contactsById[id]
            Peer(
                id = id,
                // The announced name is signed; the BLE name is an unsigned 8-byte hint.
                name = contact?.name?.takeIf { it.isNotBlank() }
                    ?: sighting?.name?.takeIf { it.isNotBlank() }
                    ?: PeerIds.short(id),
                isSharingInternet = contact?.isSharingInternet ?: sighting?.isSharingInternet ?: false,
                rssi = sighting?.rssi,
                hops = contact?.hops,
                linked = id in linked,
            )
        }.sortedWith(
            compareByDescending<Peer> { it.canProvideInternet }
                .thenByDescending { it.linked }
                .thenByDescending { it.nearby }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.hops ?: Int.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
    }

    fun expire(contacts: Map<String, Contact>, nowMs: Long): Map<String, Contact> =
        contacts.filterValues { nowMs - it.lastSeenMs <= CONTACT_TIMEOUT_MS }
}
