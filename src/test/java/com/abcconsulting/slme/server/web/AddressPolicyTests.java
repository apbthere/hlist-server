package com.abcconsulting.slme.server.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressPolicyTests {

	private static boolean allowed(String address) throws Exception {
		return AddressPolicy.PUBLIC_ONLY.allows(InetAddress.getByName(address));
	}

	@Test
	void refusesTheServerItselfAndPrivateNetworks() throws Exception {
		for (String address : new String[] {"127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.5.4", "192.168.0.17",
				"169.254.169.254", "100.64.0.1", "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1"}) {
			assertFalse(allowed(address), address);
		}
	}

	@Test
	void allowsPublicAddresses() throws Exception {
		for (String address : new String[] {"8.8.8.8", "47.198.64.44", "172.32.0.1", "2606:4700:4700::1111"}) {
			assertTrue(allowed(address), address);
		}
	}
}
