package com.example.aidatabaseassistant.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class SsrfProtection {

    @Value("${security.ssrf.block-private-hosts:true}")
    private boolean blockPrivateHosts;
    @Value("${security.ssrf.allowed-hosts:}")
    private String allowedHosts;

    public void validateHost(String host) {
        resolveAndValidate(host);
    }

    public InetAddress resolveValidatedAddress(String host) {
        return resolveAndValidate(host);
    }

    private InetAddress resolveAndValidate(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host không được để trống");
        }

        String normalizedHost = host.trim();

        if (isAllowedHost(normalizedHost)) {
            try {
                return InetAddress.getAllByName(normalizedHost)[0];
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Không thể phân giải host");
            }
        }

        if (blockPrivateHosts
                && (normalizedHost.equalsIgnoreCase("localhost")
                || normalizedHost.equalsIgnoreCase("localhost.localdomain"))) {
            throw new IllegalArgumentException("Host không được phép");
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);

            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new IllegalArgumentException("Host không được phép");
                }
            }

            return addresses[0];

        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Không thể phân giải host");
        }
    }

    private boolean isBlockedAddress(InetAddress address) {

        // Cloud metadata endpoint (VD: 169.254.169.254 cua AWS/GCP) va
        // any-local LUON bi chan, ke ca khi block-private-hosts=false cho
        // dev local - day la muc tieu SSRF nguy hiem nhat, khong co ly do
        // hop le nao de 1 DB connection tro toi day.
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }

        if (!blockPrivateHosts) {
            return false; // cho phep loopback/private/site-local khi dev local
        }

        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || isPrivateOrReserved(address);
    }

    private boolean isPrivateOrReserved(InetAddress address) {
        byte[] bytes = address.getAddress();

        if (bytes.length == 4) {
            int a = bytes[0] & 0xFF;
            int b = bytes[1] & 0xFF;

            if (a == 100 && b >= 64 && b <= 127) return true; // CGN
            if (a == 169 && b == 254) return true;
            if (a == 127) return true;
            if (a == 0) return true;
        }

        return false;
    }

    private boolean isAllowedHost(String host) {
        if (allowedHosts == null || allowedHosts.isBlank()) {
            return false;
        }

        return java.util.Arrays.stream(allowedHosts.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .anyMatch(value -> value.equalsIgnoreCase(host));
    }
}