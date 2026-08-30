package com.example.aidatabaseassistant.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateRoleRequest {
    @NotBlank(message = "role không được để trống")
    private String role; // "ADMIN" hoặc "USER"
}