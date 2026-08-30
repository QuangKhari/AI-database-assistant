package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class SuggestedQuestionsResponse {
    private List<String> questions;
    private String source; // "ai" | "template" | "cache"
}