package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProfileRequest {
    @Size(max = 100)
    private String displayName;

    @NotBlank
    @Email
    private String email;
}
