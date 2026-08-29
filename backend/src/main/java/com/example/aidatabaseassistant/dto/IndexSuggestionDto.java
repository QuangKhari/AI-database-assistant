package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class IndexSuggestionDto {

    private String table;
    private List<String> columns;
    private String reason;

    // Cau CREATE INDEX san sang de copy chay thu - KHONG tu dong thuc thi,
    // chi hien thi de nguoi dung tu quyet dinh (thay doi DDL tren DB that
    // cua nguoi dung la hanh dong nhay cam, khong nen tu dong hoa).
    private String createIndexSql;
}