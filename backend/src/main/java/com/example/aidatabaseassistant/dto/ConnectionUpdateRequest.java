package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConnectionUpdateRequest {
    @NotBlank
    private String name;

    @NotBlank
    private String host;

    @NotNull
    private Integer port;

    // Xem giai thich chi tiet o ConnectionRequest.databaseName - cung 1 ly do
    // (chan JDBC URL injection qua ten database).
    @NotBlank
    @Size(max = 64)
    @Pattern(
            regexp = "^[a-zA-Z0-9_-]+$",
            message = "Tên database chỉ được chứa chữ cái, số, dấu gạch dưới hoặc gạch ngang"
    )
    private String databaseName;

    @NotBlank
    private String username;

    private String password;
}