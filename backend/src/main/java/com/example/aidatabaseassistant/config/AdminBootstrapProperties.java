package com.example.aidatabaseassistant.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.admin.bootstrap")
public class AdminBootstrapProperties {
    private boolean enabled;
    private String username;
    private String email;
    private String password;
    private String displayName = "Quản trị viên";
}
