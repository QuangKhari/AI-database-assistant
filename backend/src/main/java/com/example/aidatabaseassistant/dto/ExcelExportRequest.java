package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ExcelExportRequest {

    @NotEmpty(message = "Danh sách cột không được để trống")
    @Size(max = 100, message = "Tối đa 100 cột")
    private List<
            @Size(max = 255, message = "Tên cột tối đa 255 ký tự")
                    String
            > columns;

    @Size(max = 100_000, message = "Tối đa 100.000 dòng")
    private List<Map<String, Object>> rows;
}
