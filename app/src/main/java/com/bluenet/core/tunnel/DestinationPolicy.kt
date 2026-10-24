package com.bluenet.core.tunnel

/** Decides which destinations a sharing device will connect to on behalf of its clients. */
fun interface DestinationPolicy {
    fun allows(address: ByteArray, port: Int): Boolean

    companion object {
        /**
         * Public internet only: refuses loopback, private, link-local, carrier-grade NAT,
         * multicast and reserved ranges, so clients cannot reach the sharer's own services or LAN.
         */
        val PUBLIC_INTERNET_ONLY = DestinationPolicy { address, port -> port != 0 && address.size == 4 && !isNonPublic(address) }

        val ALLOW_ALL = DestinationPolicy { _, _ -> true }

        fun isNonPublic(address: ByteArray): Boolean {
            val a = address[0].toInt() and 0xFF
            val b = address[1].toInt() and 0xFF
            return a == 0 ||
                a == 10 ||
                a == 127 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 192 && b == 0 && (address[2].toInt() and 0xFF) == 0) ||
                (a == 198 && (b == 18 || b == 19)) ||
                a >= 224
        }
    }
}
