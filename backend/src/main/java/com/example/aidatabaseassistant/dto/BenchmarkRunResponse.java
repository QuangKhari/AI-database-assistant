package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BenchmarkRunResponse {
    private int totalQuestions;
    private int correctCount;
    private double accuracy;
    private List<BenchmarkResultDetail> details;
}