package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UpdateProfileRequest {
    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không hợp lệ") private String email;
}
