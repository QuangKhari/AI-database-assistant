package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ChartSuggestionRequest {

    @NotEmpty
    private List<String> columns;

    @NotNull
    private List<Map<String, Object>> rows;

    // TUY CHON - khi FE truyen kem connectionId cua ket qua truy van nay,
    // backend se doi chieu schema THAT (PK/FK) de loai cot khoa khoi measure
    // chinh xac hon, thay vi chi doan theo ten cot. Khong truyen van hoat
    // dong binh thuong nhu truoc (fallback ve doan ten).
    private Long connectionId;
}