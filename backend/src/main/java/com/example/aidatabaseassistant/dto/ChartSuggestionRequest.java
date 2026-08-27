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
}