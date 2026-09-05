package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record QueryExecuteRequest(
        @NotNull(message = "Thiếu message chứa SQL preview")
        Long assistantMessageId,

        @Min(value = 1, message = "Query timeout phải từ 1 giây")
        @Max(value = 30, message = "Query timeout không được vượt quá 30 giây")
        Integer timeoutSeconds) {
}
