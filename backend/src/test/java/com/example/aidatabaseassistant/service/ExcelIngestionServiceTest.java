package com.example.aidatabaseassistant.service;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExcelIngestionServiceTest {

    private ExcelIngestionService excelIngestionService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {

        excelIngestionService =
                new ExcelIngestionService();

        ReflectionTestUtils.setField(
                excelIngestionService,
                "excelStorageDir",
                tempDir.toString()
        );
    }

    @Test
    void ingest_shouldRejectNullFile() {

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> excelIngestionService.ingest(
                                null,
                                1L
                        )
                );

        assertEquals(
                "Vui lòng chọn 1 file Excel (.xlsx)",
                exception.getMessage()
        );
    }

    @Test
    void ingest_shouldRejectEmptyFile() {

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "test.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[0]
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> excelIngestionService.ingest(
                                file,
                                1L
                        )
                );

        assertEquals(
                "Vui lòng chọn 1 file Excel (.xlsx)",
                exception.getMessage()
        );
    }

    @Test
    void ingest_shouldRejectNonExcelFile() {

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "test.txt",
                        "text/plain",
                        "hello".getBytes()
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> excelIngestionService.ingest(
                                file,
                                1L
                        )
                );

        assertEquals(
                "Chỉ hỗ trợ file .xlsx (không hỗ trợ .xls)",
                exception.getMessage()
        );
    }

    @Test
    void ingest_shouldRejectXlsFile() {

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "test.xls",
                        "application/vnd.ms-excel",
                        new byte[]{1, 2, 3}
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> excelIngestionService.ingest(
                                file,
                                1L
                        )
                );

        assertEquals(
                "Chỉ hỗ trợ file .xlsx (không hỗ trợ .xls)",
                exception.getMessage()
        );
    }

    @Test
    void ingest_shouldRejectFileLargerThan20MB() {

        byte[] data =
                new byte[
                        20 * 1024 * 1024 + 1
                        ];

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "large.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        data
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> excelIngestionService.ingest(
                                file,
                                1L
                        )
                );

        assertEquals(
                "File Excel vượt quá giới hạn 20MB",
                exception.getMessage()
        );
    }

    @Test
    void ingest_shouldRejectMalformedExcelFile() {

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "broken.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "this is not an excel file".getBytes()
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> excelIngestionService.ingest(
                                file,
                                1L
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains("Không đọc được file Excel")
        );

        assertFalse(
                Files.exists(
                        tempDir.resolve("user_1")
                                .resolve("broken.xlsx")
                )
        );
    }

    @Test
    void ingest_shouldCreateDuckDbFile_whenExcelIsValid()
            throws Exception {

        byte[] excelBytes =
                createValidWorkbook(
                        "Customers",
                        new String[][]{
                                {"id", "name"},
                                {"1", "Alice"},
                                {"2", "Bob"}
                        }
                );

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "customers.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        excelBytes
                );

        String databasePath =
                excelIngestionService.ingest(
                        file,
                        1L
                );

        assertNotNull(databasePath);

        Path duckDbFile =
                Path.of(databasePath);

        assertTrue(
                Files.exists(duckDbFile),
                "DuckDB file should be created"
        );

        assertTrue(
                databasePath.endsWith(".duckdb")
        );

        assertTrue(
                databasePath.contains("user_1")
        );

        /*
         * File Excel gốc phải được xóa sau khi
         * dữ liệu đã được ingest vào DuckDB.
         */
        Path userDirectory =
                tempDir.resolve("user_1");

        try (var files =
                     Files.list(userDirectory)) {

            assertEquals(
                    1,
                    files.count(),
                    "Only the DuckDB file should remain"
            );
        }
    }

    @Test
    void ingest_shouldSupportVietnameseSheetName()
            throws Exception {

        byte[] excelBytes =
                createValidWorkbook(
                        "Khách hàng",
                        new String[][]{
                                {"id", "name"},
                                {"1", "Nguyen Van A"}
                        }
                );

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "customers.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        excelBytes
                );

        String databasePath =
                excelIngestionService.ingest(
                        file,
                        10L
                );

        assertNotNull(databasePath);

        assertTrue(
                Files.exists(
                        Path.of(databasePath)
                )
        );
    }

    @Test
    void ingest_shouldCreateDifferentDatabaseFiles_forDifferentUploads()
            throws Exception {

        byte[] excelBytes =
                createValidWorkbook(
                        "Customers",
                        new String[][]{
                                {"id", "name"},
                                {"1", "Alice"}
                        }
                );

        MockMultipartFile file1 =
                new MockMultipartFile(
                        "file",
                        "customers.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        excelBytes
                );

        MockMultipartFile file2 =
                new MockMultipartFile(
                        "file",
                        "customers.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        excelBytes
                );

        String path1 =
                excelIngestionService.ingest(
                        file1,
                        1L
                );

        String path2 =
                excelIngestionService.ingest(
                        file2,
                        1L
                );

        assertNotEquals(
                path1,
                path2
        );

        assertTrue(
                Files.exists(Path.of(path1))
        );

        assertTrue(
                Files.exists(Path.of(path2))
        );
    }

    @Test
    void ingest_shouldKeepDifferentUsersInDifferentDirectories()
            throws Exception {

        byte[] excelBytes =
                createValidWorkbook(
                        "Customers",
                        new String[][]{
                                {"id", "name"},
                                {"1", "Alice"}
                        }
                );

        MockMultipartFile file1 =
                new MockMultipartFile(
                        "file",
                        "customers.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        excelBytes
                );

        MockMultipartFile file2 =
                new MockMultipartFile(
                        "file",
                        "customers.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        excelBytes
                );

        String pathUser1 =
                excelIngestionService.ingest(
                        file1,
                        1L
                );

        String pathUser2 =
                excelIngestionService.ingest(
                        file2,
                        2L
                );

        assertTrue(
                pathUser1.contains("user_1")
        );

        assertTrue(
                pathUser2.contains("user_2")
        );

        assertNotEquals(
                pathUser1,
                pathUser2
        );
    }

    @Test
    void ingest_shouldDeleteSourceXlsx_afterSuccessfulIngestion()
            throws Exception {

        byte[] excelBytes =
                createValidWorkbook(
                        "Products",
                        new String[][]{
                                {"id", "product"},
                                {"1", "Laptop"}
                        }
                );

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "products.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        excelBytes
                );

        String databasePath =
                excelIngestionService.ingest(
                        file,
                        1L
                );

        Path duckDbPath =
                Path.of(databasePath);

        Path userDirectory =
                duckDbPath.getParent();

        try (var files =
                     Files.list(userDirectory)) {

            var fileList =
                    files.toList();

            assertEquals(
                    1,
                    fileList.size()
            );

            assertTrue(
                    fileList.get(0)
                            .toString()
                            .endsWith(".duckdb")
            );
        }
    }

    @Test
    void deleteDuckDbFile_shouldDeleteFile_insideExcelStorage()
            throws IOException {

        Path userDirectory =
                tempDir.resolve("user_1");

        Files.createDirectories(userDirectory);

        Path duckDbFile =
                userDirectory.resolve("test.duckdb");

        Files.createFile(duckDbFile);

        assertTrue(
                Files.exists(duckDbFile)
        );

        excelIngestionService.deleteDuckDbFile(
                duckDbFile.toString()
        );

        assertFalse(
                Files.exists(duckDbFile)
        );
    }

    @Test
    void deleteDuckDbFile_shouldRejectPath_outsideExcelStorage()
            throws IOException {

        Path outsideFile =
                tempDir.getParent()
                        .resolve("outside.duckdb");

        Files.createDirectories(
                outsideFile.getParent()
        );

        Files.createFile(outsideFile);

        try {
            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> excelIngestionService.deleteDuckDbFile(
                                    outsideFile.toString()
                            )
                    );

            assertEquals(
                    "Đường dẫn file Excel không hợp lệ",
                    exception.getMessage()
            );

            assertTrue(
                    Files.exists(outsideFile)
            );

        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void deleteDuckDbFile_shouldRejectNonDuckDbFile()
            throws IOException {

        Path userDirectory =
                tempDir.resolve("user_1");

        Files.createDirectories(userDirectory);

        Path xlsxFile =
                userDirectory.resolve("test.xlsx");

        Files.createFile(xlsxFile);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> excelIngestionService.deleteDuckDbFile(
                                xlsxFile.toString()
                        )
                );

        assertEquals(
                "Chỉ được xóa file DuckDB của Excel",
                exception.getMessage()
        );

        assertTrue(
                Files.exists(xlsxFile)
        );
    }

    @Test
    void deleteDuckDbFile_shouldDoNothing_whenPathIsNull() {

        assertDoesNotThrow(
                () -> excelIngestionService.deleteDuckDbFile(null)
        );
    }

    @Test
    void deleteDuckDbFile_shouldDoNothing_whenPathIsBlank() {

        assertDoesNotThrow(
                () -> excelIngestionService.deleteDuckDbFile("   ")
        );
    }

    private byte[] createValidWorkbook(
            String sheetName,
            String[][] data
    ) throws IOException {

        try (
                Workbook workbook =
                        new XSSFWorkbook();

                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()
        ) {

            var sheet =
                    workbook.createSheet(
                            sheetName
                    );

            for (int rowIndex = 0;
                 rowIndex < data.length;
                 rowIndex++) {

                var row =
                        sheet.createRow(rowIndex);

                for (int columnIndex = 0;
                     columnIndex < data[rowIndex].length;
                     columnIndex++) {

                    row.createCell(columnIndex)
                            .setCellValue(
                                    data[rowIndex][columnIndex]
                            );
                }
            }

            workbook.write(output);

            return output.toByteArray();
        }
    }
}