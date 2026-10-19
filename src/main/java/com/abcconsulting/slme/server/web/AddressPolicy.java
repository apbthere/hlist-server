package com.abcconsulting.slme.server.web;

import java.net.Inet6Address;
import java.net.InetAddress;

/** Which network addresses the icon fetcher may contact. */
@FunctionalInterface
public interface AddressPolicy {

    boolean allows(InetAddress address);

    /**
     * Public internet addresses only: never the NAS itself, the home network or other private ranges, so a store
     * site (or a redirect from it) can't make the server contact internal machines.
     */
    AddressPolicy PUBLIC_ONLY = address -> !(address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()
            || isCarrierGradeNat(address)
            || isUniqueLocalIpv6(address));

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }
}
