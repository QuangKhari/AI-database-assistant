package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SqlExplanationStep {

    // Phan SQL cua menh de nay, vi du: "SELECT full_name, price" hoac
    // "JOIN orders o ON o.customer_id = c.id"
    private String clause;

    // Giai thich menh de do bang tieng Viet, don gian, de hieu
    private String explanation;
}