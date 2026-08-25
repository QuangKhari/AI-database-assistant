package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConnectionUpdateRequest {
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 253)
    @Pattern(regexp = "^[A-Za-z0-9.:-]+$", message = "host không đúng định dạng")
    private String host;

    @NotNull
    @Min(1)
    @Max(65535)
    private Integer port;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[A-Za-z0-9_$]+$", message = "database name chỉ được chứa chữ, số, _, $")
    private String databaseName;

    @NotBlank
    @Size(max = 64)
    private String username;

    @Size(max = 256)
    private String password;
}
