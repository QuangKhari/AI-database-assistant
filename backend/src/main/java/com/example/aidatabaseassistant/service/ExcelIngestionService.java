package com.example.aidatabaseassistant.service;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nap 1 file .xlsx nguoi dung upload thanh 1 file .duckdb THAT tren dia -
 * moi sheet trong Excel tro thanh 1 bang SQL. File .duckdb nay sau do duoc
 * dung y het nhu 1 "database dich" binh thuong qua TargetDatabaseClient
 * (dbType = "excel"), toan bo pipeline SQL/JSqlParser/DatabaseMetaData phia
 * sau khong can biet du lieu von tu Excel ma ra.
 *
 * QUAN TRONG: chi ingest 1 LAN luc upload. Cac lan hoi sau KHONG doc lai
 * file .xlsx goc va KHONG can load lai extension "excel" cua DuckDB - chi
 * mo thang file .duckdb da tao san (nhanh hon, va khong phu thuoc mang lup
 * INSTALL extension moi lan).
 */
@Service
public class ExcelIngestionService {

    // Du cho dataset thesis, tranh nguoi dung vo tinh upload file qua lon
    // gay OOM luc DuckDB doc toan bo sheet vao bo nho de ghi ra .duckdb.
    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^a-zA-Z0-9_]");

    @Value("${app.storage.excel-dir:./data/excel-dbs}")
    private String excelStorageDir;

    /**
     * @return duong dan TUYET DOI toi file .duckdb da tao - gia tri nay se
     *         duoc luu vao cot databaseName cua DatabaseConnection.
     */
    public String ingest(MultipartFile file, Long userId) {
        validateFile(file);

        Path userDir = Path.of(excelStorageDir, "user_" + userId);
        try {
            Files.createDirectories(userDir);
        } catch (IOException e) {
            throw new IllegalStateException("Không thể tạo thư mục lưu trữ file Excel", e);
        }

        String uuid = UUID.randomUUID().toString();
        Path sourceXlsx = userDir.resolve(uuid + ".xlsx");
        Path duckDbFile = userDir.resolve(uuid + ".duckdb");

        try {
            file.transferTo(sourceXlsx);
        } catch (IOException e) {
            throw new IllegalStateException("Không thể lưu file Excel đã tải lên", e);
        }

        List<String> sheetNames = readSheetNames(sourceXlsx);
        if (sheetNames.isEmpty()) {
            deleteQuietly(sourceXlsx);
            throw new IllegalArgumentException("File Excel không có sheet nào chứa dữ liệu");
        }

        try {
            buildDuckDbFile(sourceXlsx, duckDbFile, sheetNames);
        } catch (SQLException e) {
            deleteQuietly(sourceXlsx);
            deleteQuietly(duckDbFile);
            throw new IllegalStateException(
                    "Không thể nạp dữ liệu Excel vào DuckDB: " + e.getMessage(), e);
        } finally {
            // Du lieu that da nam trong file .duckdb - khong can giu 2 ban
            // sao (.xlsx + .duckdb) chiem dia.
            deleteQuietly(sourceXlsx);
        }

        return duckDbFile.toAbsolutePath().toString();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn 1 file Excel (.xlsx)");
        }
        String original = file.getOriginalFilename();
        if (original == null || !original.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("Chỉ hỗ trợ file .xlsx (không hỗ trợ .xls)");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File Excel vượt quá giới hạn 20MB");
        }
    }

    private List<String> readSheetNames(Path xlsxPath) {
        List<String> names = new ArrayList<>();
        try (InputStream in = Files.newInputStream(xlsxPath);
             Workbook workbook = WorkbookFactory.create(in)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Không đọc được file Excel, file có thể bị lỗi định dạng", e);
        }
        return names;
    }

    private void buildDuckDbFile(Path sourceXlsx, Path duckDbFile, List<String> sheetNames)
            throws SQLException {
        String url = "jdbc:duckdb:" + duckDbFile.toAbsolutePath();
        Set<String> usedTableNames = new HashSet<>();

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSTALL excel");
            stmt.execute("LOAD excel");

            for (String sheetName : sheetNames) {
                String tableName = toSafeTableName(sheetName, usedTableNames);
                String escapedPath = sourceXlsx.toAbsolutePath().toString().replace("'", "''");
                String escapedSheet = sheetName.replace("'", "''");

                String createSql = "CREATE TABLE \"" + tableName + "\" AS "
                        + "SELECT * FROM read_xlsx('" + escapedPath + "', "
                        + "sheet='" + escapedSheet + "', ignore_errors=true)";
                stmt.execute(createSql);
            }
        }
    }

    /**
     * Ten sheet tieng Viet co dau/khoang trang -> ten bang SQL an toan: bo
     * dau, thay ky tu khong hop le bang "_", khong bat dau bang so, va
     * khong trung ten voi bang da tao truoc do trong cung file.
     */
    private String toSafeTableName(String sheetName, Set<String> usedNames) {
        String normalized = Normalizer.normalize(sheetName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String safe = UNSAFE_CHARS.matcher(normalized).replaceAll("_").toLowerCase();

        if (safe.isBlank()) {
            safe = "sheet";
        }
        if (Character.isDigit(safe.charAt(0))) {
            safe = "t_" + safe;
        }

        String candidate = safe;
        int suffix = 1;
        while (!usedNames.add(candidate)) {
            candidate = safe + "_" + suffix++;
        }
        return candidate;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup - khong lam vo luong ingest chinh.
        }
    }

    public void deleteDuckDbFile(String duckDbFilePath) {
        if (duckDbFilePath == null || duckDbFilePath.isBlank()) {
            return;
        }

        Path storageRoot = Path.of(excelStorageDir)
                .toAbsolutePath()
                .normalize();

        Path target = Path.of(duckDbFilePath)
                .toAbsolutePath()
                .normalize();

        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException(
                    "Đường dẫn file Excel không hợp lệ"
            );
        }

        if (!target.getFileName().toString().endsWith(".duckdb")) {
            throw new IllegalArgumentException(
                    "Chỉ được xóa file DuckDB của Excel"
            );
        }

        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Không thể xóa file DuckDB của Excel",
                    e
            );
        }
    }
}