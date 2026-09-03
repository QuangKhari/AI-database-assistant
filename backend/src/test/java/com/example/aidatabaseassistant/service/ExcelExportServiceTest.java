package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ExcelExportRequest;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelExportServiceTest {

    private final ExcelExportService service =
            new ExcelExportService();

    @Test
    void export_shouldCreateValidExcelFile() throws Exception {

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of(
                                "id",
                                "name",
                                "active",
                                "score"
                        ),
                        List.of(
                                Map.of(
                                        "id", 1,
                                        "name", "Alice",
                                        "active", true,
                                        "score", 9.5
                                ),
                                Map.of(
                                        "id", 2,
                                        "name", "Bob",
                                        "active", false,
                                        "score", 8
                                )
                        )
                );

        byte[] bytes =
                service.export(request);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                new ByteArrayInputStream(bytes)
                        )
        ) {

            Sheet sheet =
                    workbook.getSheet("Query Result");

            assertNotNull(sheet);

            assertEquals(
                    2,
                    sheet.getLastRowNum()
            );

            Row header =
                    sheet.getRow(0);

            assertEquals(
                    "id",
                    header.getCell(0)
                            .getStringCellValue()
            );

            assertEquals(
                    "name",
                    header.getCell(1)
                            .getStringCellValue()
            );

            Row firstRow =
                    sheet.getRow(1);

            assertEquals(
                    1,
                    firstRow.getCell(0)
                            .getNumericCellValue()
            );

            assertEquals(
                    "Alice",
                    firstRow.getCell(1)
                            .getStringCellValue()
            );

            assertTrue(
                    firstRow.getCell(2)
                            .getBooleanCellValue()
            );

            assertEquals(
                    9.5,
                    firstRow.getCell(3)
                            .getNumericCellValue()
            );
        }
    }

    @Test
    void export_shouldHandleNullValue() throws Exception {

        Map<String, Object> row =
                new HashMap<>();

        row.put("id", 1);
        row.put("name", null);

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of("id", "name"),
                        List.of(row)
                );

        byte[] bytes =
                service.export(request);

        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                new ByteArrayInputStream(bytes)
                        )
        ) {

            Cell cell =
                    workbook
                            .getSheetAt(0)
                            .getRow(1)
                            .getCell(1);

            assertEquals(
                    CellType.BLANK,
                    cell.getCellType()
            );
        }
    }

    @Test
    void export_shouldPreventFormulaInjection()
            throws Exception {

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of("value"),
                        List.of(
                                Map.of(
                                        "value",
                                        "=HYPERLINK(\"https://evil.com\")"
                                ),
                                Map.of(
                                        "value",
                                        "+SUM(1,2)"
                                ),
                                Map.of(
                                        "value",
                                        "@cmd"
                                )
                        )
                );

        byte[] bytes =
                service.export(request);

        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                new ByteArrayInputStream(bytes)
                        )
        ) {

            Sheet sheet =
                    workbook.getSheetAt(0);

            for (int i = 1; i <= 3; i++) {

                Cell cell =
                        sheet.getRow(i)
                                .getCell(0);

                assertEquals(
                        CellType.STRING,
                        cell.getCellType()
                );

                assertTrue(
                        cell.getStringCellValue()
                                .startsWith("'")
                );
            }
        }
    }

    @Test
    void export_shouldRejectTooManyColumns() {

        List<String> columns =
                new ArrayList<>();

        for (int i = 0; i < 101; i++) {
            columns.add("column_" + i);
        }

        ExcelExportRequest request =
                new ExcelExportRequest(
                        columns,
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.export(request)
        );
    }

    @Test
    void export_shouldRejectTooManyRows() {

        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (int i = 0;
             i < 100_001;
             i++) {

            rows.add(
                    Map.of("id", i)
            );
        }

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of("id"),
                        rows
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.export(request)
        );
    }

    @Test
    void export_shouldRejectTooManyCells() {

        List<String> columns =
                new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            columns.add("c" + i);
        }

        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (int i = 0;
             i < 20_001;
             i++) {

            rows.add(
                    Map.of("c0", i)
            );
        }

        ExcelExportRequest request =
                new ExcelExportRequest(
                        columns,
                        rows
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.export(request)
        );
    }

    @Test
    void export_shouldTruncateLongText()
            throws Exception {

        String longText =
                "x".repeat(40_000);

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of("value"),
                        List.of(
                                Map.of(
                                        "value",
                                        longText
                                )
                        )
                );

        byte[] bytes =
                service.export(request);

        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                new ByteArrayInputStream(bytes)
                        )
        ) {

            String result =
                    workbook
                            .getSheetAt(0)
                            .getRow(1)
                            .getCell(0)
                            .getStringCellValue();

            assertEquals(
                    32_767,
                    result.length()
            );
        }
    }

    @Test
    void export_shouldRejectNullRequest() {

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.export(null)
                );

        assertEquals(
                "Danh sách cột không được để trống",
                exception.getMessage()
        );
    }

    @Test
    void export_shouldRejectNullColumns() {

        ExcelExportRequest request =
                new ExcelExportRequest(
                        null,
                        List.of()
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.export(request)
                );

        assertEquals(
                "Danh sách cột không được để trống",
                exception.getMessage()
        );
    }

    @Test
    void export_shouldRejectEmptyColumns() {

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of(),
                        List.of()
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.export(request)
                );

        assertEquals(
                "Danh sách cột không được để trống",
                exception.getMessage()
        );
    }

    @Test
    void export_shouldAllowNullRows() throws Exception {

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of("id", "name"),
                        null
                );

        byte[] bytes =
                service.export(request);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                new ByteArrayInputStream(bytes)
                        )
        ) {

            Sheet sheet =
                    workbook.getSheet("Query Result");

            assertNotNull(sheet);

            assertEquals(
                    0,
                    sheet.getLastRowNum()
            );

            assertNotNull(
                    sheet.getRow(0)
            );
        }
    }

    @Test
    void export_shouldRejectNullColumnName() {

        ExcelExportRequest request =
                new ExcelExportRequest(
                        Arrays.asList("id", null),
                        List.of()
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.export(request)
                );

        assertEquals(
                "Tên cột không hợp lệ",
                exception.getMessage()
        );
    }

    @Test
    void export_shouldRejectTooLongColumnName() {

        String longColumnName =
                "x".repeat(256);

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of(longColumnName),
                        List.of()
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.export(request)
                );

        assertEquals(
                "Tên cột không hợp lệ",
                exception.getMessage()
        );
    }

    @Test
    void export_shouldWriteBigDecimalAndBigIntegerAsText()
            throws Exception {

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of("decimal", "bigInteger"),
                        List.of(
                                Map.of(
                                        "decimal",
                                        new java.math.BigDecimal(
                                                "12345678901234567890.123456789"
                                        ),
                                        "bigInteger",
                                        new java.math.BigInteger(
                                                "999999999999999999999999999999"
                                        )
                                )
                        )
                );

        byte[] bytes =
                service.export(request);

        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                new ByteArrayInputStream(bytes)
                        )
        ) {

            Row row =
                    workbook
                            .getSheetAt(0)
                            .getRow(1);

            assertEquals(
                    CellType.STRING,
                    row.getCell(0).getCellType()
            );

            assertEquals(
                    "12345678901234567890.123456789",
                    row.getCell(0).getStringCellValue()
            );

            assertEquals(
                    CellType.STRING,
                    row.getCell(1).getCellType()
            );

            assertEquals(
                    "999999999999999999999999999999",
                    row.getCell(1).getStringCellValue()
            );
        }
    }

    @Test
    void export_shouldWriteDateAndCalendar()
            throws Exception {

        Date date =
                new Date(0);

        Calendar calendar =
                Calendar.getInstance();

        calendar.setTimeInMillis(0);

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of("date", "calendar"),
                        List.of(
                                Map.of(
                                        "date", date,
                                        "calendar", calendar
                                )
                        )
                );

        byte[] bytes =
                service.export(request);

        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                new ByteArrayInputStream(bytes)
                        )
        ) {

            Row row =
                    workbook
                            .getSheetAt(0)
                            .getRow(1);

            assertEquals(
                    CellType.NUMERIC,
                    row.getCell(0).getCellType()
            );

            assertEquals(
                    CellType.NUMERIC,
                    row.getCell(1).getCellType()
            );

            assertEquals(
                    date,
                    row.getCell(0).getDateCellValue()
            );

            assertEquals(
                    calendar.getTime(),
                    row.getCell(1).getDateCellValue()
            );
        }
    }

    @Test
    void export_shouldWriteTemporalAndOtherObjectAsText()
            throws Exception {

        ExcelExportRequest request =
                new ExcelExportRequest(
                        List.of("temporal", "object"),
                        List.of(
                                Map.of(
                                        "temporal",
                                        java.time.LocalDate.of(
                                                2026,
                                                9,
                                                3
                                        ),
                                        "object",
                                        new StringBuilder("hello")
                                )
                        )
                );

        byte[] bytes =
                service.export(request);

        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                new ByteArrayInputStream(bytes)
                        )
        ) {

            Row row =
                    workbook
                            .getSheetAt(0)
                            .getRow(1);

            assertEquals(
                    CellType.STRING,
                    row.getCell(0).getCellType()
            );

            assertEquals(
                    "2026-09-03",
                    row.getCell(0).getStringCellValue()
            );

            assertEquals(
                    CellType.STRING,
                    row.getCell(1).getCellType()
            );

            assertEquals(
                    "hello",
                    row.getCell(1).getStringCellValue()
            );
        }
    }
}