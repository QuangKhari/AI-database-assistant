package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 1 dong trong ket qua EXPLAIN cua MySQL (chi giu lai cac cot quan trong
 * nhat de phat hien van de hieu nang, bo qua partitions/key_len/ref vi
 * khong can cho muc dich phan tich cua tinh nang nay).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ExplainRowDto {
    private Integer id;
    private String selectType;
    private String table;
    private String type;
    private String possibleKeys;
    private String key;
    private Long rows;
    private String extra;
}