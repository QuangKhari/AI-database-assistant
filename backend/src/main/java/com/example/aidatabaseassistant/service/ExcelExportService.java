package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ExcelExportRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class ExcelExportService {

    static final int MAX_COLUMNS = 100;
    static final int MAX_ROWS = 100_000;
    static final long MAX_CELLS = 2_000_000L;

    // Excel giới hạn 32,767 ký tự / cell
    static final int MAX_CELL_TEXT_LENGTH = 32_767;

    private static final String SHEET_NAME = "Query Result";

    public byte[] export(ExcelExportRequest request) {

        validate(request);

        try (
                SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {

            // Nén file tạm của SXSSF
            workbook.setCompressTempFiles(true);

            Sheet sheet = workbook.createSheet(SHEET_NAME);

            writeHeader(
                    workbook,
                    sheet,
                    request.getColumns()
            );

            writeRows(
                    sheet,
                    request.getColumns(),
                    request.getRows()
            );

            workbook.write(output);

            // Xóa temporary files của SXSSF
            workbook.dispose();

            return output.toByteArray();

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Không thể tạo file Excel",
                    e
            );
        }
    }

    private void validate(ExcelExportRequest request) {

        if (request == null
                || request.getColumns() == null
                || request.getColumns().isEmpty()) {

            throw new IllegalArgumentException(
                    "Danh sách cột không được để trống"
            );
        }

        if (request.getColumns().size() > MAX_COLUMNS) {

            throw new IllegalArgumentException(
                    "Tối đa " + MAX_COLUMNS + " cột"
            );
        }

        List<Map<String, Object>> rows = request.getRows();

        if (rows == null) {
            return;
        }

        if (rows.size() > MAX_ROWS) {

            throw new IllegalArgumentException(
                    "Tối đa " + MAX_ROWS
                            + " dòng cho mỗi lần xuất Excel"
            );
        }

        long totalCells =
                (long) rows.size()
                        * request.getColumns().size();

        if (totalCells > MAX_CELLS) {

            throw new IllegalArgumentException(
                    "Kết quả quá lớn để xuất Excel "
                            + "(tối đa "
                            + MAX_CELLS
                            + " ô)"
            );
        }

        for (String column : request.getColumns()) {

            if (column == null
                    || column.length() > 255) {

                throw new IllegalArgumentException(
                        "Tên cột không hợp lệ"
                );
            }
        }
    }

    private void writeHeader(
            Workbook workbook,
            Sheet sheet,
            List<String> columns
    ) {

        Row header = sheet.createRow(0);

        CellStyle style =
                workbook.createCellStyle();

        Font font =
                workbook.createFont();

        font.setBold(true);

        style.setFont(font);

        for (int i = 0; i < columns.size(); i++) {

            Cell cell =
                    header.createCell(i, CellType.STRING);

            cell.setCellValue(
                    truncate(columns.get(i))
            );

            cell.setCellStyle(style);
        }
    }

    private void writeRows(
            Sheet sheet,
            List<String> columns,
            List<Map<String, Object>> rows
    ) {

        if (rows == null) {
            return;
        }

        for (int rowIndex = 0;
             rowIndex < rows.size();
             rowIndex++) {

            Map<String, Object> data =
                    rows.get(rowIndex);

            Row row =
                    sheet.createRow(rowIndex + 1);

            for (int columnIndex = 0;
                 columnIndex < columns.size();
                 columnIndex++) {

                String column =
                        columns.get(columnIndex);

                Object value =
                        data == null
                                ? null
                                : data.get(column);

                writeValue(
                        row.createCell(columnIndex),
                        value
                );
            }
        }
    }

    private void writeValue(
            Cell cell,
            Object value
    ) {

        if (value == null) {

            cell.setBlank();

            return;
        }

        /*
         * BigDecimal / BigInteger không convert sang double
         * vì có thể mất precision.
         */
        if (value instanceof BigDecimal
                || value instanceof BigInteger) {

            cell.setCellValue(
                    value.toString()
            );

            return;
        }

        if (value instanceof Number number) {

            cell.setCellValue(
                    number.doubleValue()
            );

            return;
        }

        if (value instanceof Boolean bool) {

            cell.setCellValue(bool);

            return;
        }

        if (value instanceof Date date) {

            cell.setCellValue(date);

            return;
        }

        if (value instanceof Calendar calendar) {

            cell.setCellValue(calendar);

            return;
        }

        if (value instanceof TemporalAccessor) {

            cell.setCellValue(
                    value.toString()
            );

            return;
        }

        /*
         * Những object còn lại convert thành String.
         */
        cell.setCellValue(
                sanitizeText(
                        String.valueOf(value)
                )
        );
    }

    /**
     * Chống Excel Formula Injection.
     *
     * Ví dụ:
     * =HYPERLINK(...)
     * +SUM(...)
     * -10
     * @cmd
     *
     * sẽ được lưu dưới dạng text.
     */
    private String sanitizeText(String value) {

        if (value.isEmpty()) {
            return value;
        }

        String safe = value;

        char first =
                safe.charAt(0);

        if (first == '='
                || first == '+'
                || first == '-'
                || first == '@') {

            safe = "'" + safe;
        }

        return truncate(safe);
    }

    private String truncate(String value) {

        if (value == null
                || value.length()
                <= MAX_CELL_TEXT_LENGTH) {

            return value;
        }

        return value.substring(
                0,
                MAX_CELL_TEXT_LENGTH
        );
    }
}
