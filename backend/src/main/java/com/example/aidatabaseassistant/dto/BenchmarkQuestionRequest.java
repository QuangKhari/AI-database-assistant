package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BenchmarkQuestionRequest {

    @NotBlank(message = "Ngôn ngữ không được để trống")
    @Pattern(
            regexp = "VI|EN",
            message = "Ngôn ngữ phải là VI hoặc EN"
    )
    private String language;

    @NotBlank(message = "Câu hỏi không được để trống")
    private String questionText;

    @NotBlank(message = "Expected SQL không được để trống")
    private String expectedSql;
}