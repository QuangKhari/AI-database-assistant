package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExplainSqlRequest {

    @NotBlank
    private String sql;

    // Tuy chon: neu co, AI se dung them ten bang/cot tu schema de giai thich
    // chinh xac hon (vi du hieu duoc "full_name" nghia la ten khach hang).
    // Neu khong truyen, van giai thich duoc dua thuan tuy tren cu phap SQL.
    private Long databaseConnectionId;
}