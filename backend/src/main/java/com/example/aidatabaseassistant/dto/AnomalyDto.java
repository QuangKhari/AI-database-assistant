package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyDto {
    private String label;
    private double value;
    // "cao bất thường" hoac "thấp bất thường"
    private String direction;
}