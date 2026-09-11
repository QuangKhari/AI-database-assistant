package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    // Chi cho phep chu/so/gach duoi/gach ngang - JdbcUrlBuilder noi truc tiep
    // gia tri nay vao chuoi JDBC URL (jdbc:mysql://host:port/DBNAME?...),
    // neu khong whitelist ky tu co the chen them tham so JDBC nguy hiem
    // (vi du "mydb?allowLoadLocalInfile=true") qua truong nay.
    @NotBlank
    @Size(max = 64)
    @Pattern(
            regexp = "^[a-zA-Z0-9_-]+$",
            message = "Tên database chỉ được chứa chữ cái, số, dấu gạch dưới hoặc gạch ngang"
    )
    private String databaseName;

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    private boolean sslEnabled = false;
}