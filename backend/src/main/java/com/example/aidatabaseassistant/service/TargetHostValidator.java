package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.TargetDatabaseProperties;
import com.example.aidatabaseassistant.exception.TargetDatabaseConnectionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
@RequiredArgsConstructor
public class TargetHostValidator {

    private final TargetDatabaseProperties properties;

    public void validate(String host) {
        if (properties.isAllowPrivateHosts()) {
            return;
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isPrivateOrSpecial(address)) {
                    throw new TargetDatabaseConnectionException(
                            "PRIVATE_HOST_BLOCKED",
                            "Địa chỉ database nội bộ không được phép trong môi trường public."
                    );
                }
            }
        } catch (UnknownHostException e) {
            throw new TargetDatabaseConnectionException(
                    "HOST_NOT_FOUND", "Không tìm thấy địa chỉ máy chủ database."
            );
        }
    }

    private boolean isPrivateOrSpecial(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            byte first = address.getAddress()[0];
            return (first & 0xFE) == 0xFC;
        }
        return false;
    }
}
