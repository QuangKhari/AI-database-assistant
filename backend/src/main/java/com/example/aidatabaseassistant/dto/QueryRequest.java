package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QueryRequest {

    @NotBlank
    private String question;

    private Long conversationId;

    @NotNull
    private Long databaseConnectionId;

    private String generatedSql;
}