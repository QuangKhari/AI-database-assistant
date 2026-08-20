package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConnectionRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String dbType;

    @NotBlank
    private String host;

    @NotNull
    private Integer port;

    @NotBlank
    private String databaseName;

    @NotBlank
    private String username;

    @NotBlank
    private String password;
}