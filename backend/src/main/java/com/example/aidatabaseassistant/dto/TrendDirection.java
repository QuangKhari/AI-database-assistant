package com.example.aidatabaseassistant.dto;

/**
 * Xu huong cua chuoi so lieu theo thoi gian. Chi duoc tinh khi truc X la
 * dang thoi gian (chartType = LINE) - voi du lieu danh muc khong co thu tu
 * (vi du Pie/Bar), khai niem "xu huong tang/giam" khong co y nghia.
 */
public enum TrendDirection {
    INCREASING,
    DECREASING,
    STABLE
}