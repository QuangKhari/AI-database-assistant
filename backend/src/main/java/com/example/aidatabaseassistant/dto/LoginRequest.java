package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank
    private String identifier;

    @NotBlank
    private String password;
}
