package com.example.aidatabaseassistant.security;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class SsrfProtection {

    public void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host không được để trống");
        }

        String normalizedHost = host.trim();

        // Block obvious local/internal hostnames
        if (normalizedHost.equalsIgnoreCase("localhost")
                || normalizedHost.equalsIgnoreCase("localhost.localdomain")) {
            throw new IllegalArgumentException("Host không được phép");
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);

            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new IllegalArgumentException("Host không được phép");
                }
            }

        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Không thể phân giải host");
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || isPrivateOrReserved(address);
    }

    private boolean isPrivateOrReserved(InetAddress address) {
        byte[] bytes = address.getAddress();

        if (bytes.length == 4) {
            int a = bytes[0] & 0xFF;
            int b = bytes[1] & 0xFF;

            // 100.64.0.0/10 - Carrier Grade NAT
            if (a == 100 && b >= 64 && b <= 127) {
                return true;
            }

            // 169.254.0.0/16
            if (a == 169 && b == 254) {
                return true;
            }

            // 127.0.0.0/8
            if (a == 127) {
                return true;
            }

            // 0.0.0.0/8
            if (a == 0) {
                return true;
            }
        }

        return false;
    }
}