package com.example.aidatabaseassistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException e) {

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                e.getMessage()
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(
            BadCredentialsException e) {

        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "Sai tên đăng nhập hoặc mật khẩu"
        );
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Map<String, Object>> handleLockedAccount(
            LockedException e) {

        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "Tài khoản đã bị khóa"
        );
    }

    /*
     * FE-BE CONTRACT FIX:
     *
     * Truoc day handler nay chi tra ve "message" voi noi dung cua LOI DAU
     * TIEN tim thay, khien form dang ky (RegisterPage.tsx) khong the
     * highlight dung o input bi loi - no doc reason.fieldErrors (xem
     * api/client.ts, api/types.ts: ApiErrorBody.fieldErrors) nhung BE
     * chua bao gio dien field nay.
     *
     * Gio day tra ve DAY DU fieldErrors: {"username": "...", "email": "..."}
     * de FE highlight DUNG tung o loi, dong thoi van giu "message" (loi
     * dau tien) de hien thi o banner loi chung cho tuong thich nguoc.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException e) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            // Neu 1 field co nhieu loi (vi du @NotBlank + @Size deu fail),
            // chi giu loi DAU TIEN cho field do - putIfAbsent tranh ghi de.
            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage() != null
                            ? fieldError.getDefaultMessage()
                            : "Giá trị không hợp lệ"
            );
        }

        String message = fieldErrors.values()
                .stream()
                .findFirst()
                .orElse("Dữ liệu không hợp lệ");

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                message,
                fieldErrors
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(
            RateLimitExceededException e) {

        return buildResponse(
                HttpStatus.TOO_MANY_REQUESTS,
                e.getMessage()
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException e) {

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Thiếu tham số bắt buộc: " + e.getParameterName()
        );
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingServletRequestPart(
            MissingServletRequestPartException e) {

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "Thiếu file bắt buộc: " + e.getRequestPartName()
        );
    }

    // Nem ra tu Spring's multipart filter TRUOC KHI request toi duoc
    // controller (vi du: file Excel > spring.servlet.multipart.max-file-size
    // trong application.properties). Khong phai IllegalArgumentException
    // nen KHONG duoc handleIllegalArgument() bat - phai co handler rieng,
    // neu khong se roi vao handleGeneral() va tra ve 500 chung chung thay
    // vi 413 ro rang cho nguoi dung.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException e) {

        // HttpStatus.PAYLOAD_TOO_LARGE bi @Deprecated tu Spring Framework
        // 7.0 (dung trong Boot 4.1), thay the boi CONTENT_TOO_LARGE theo
        // ten goi moi cua RFC 9110 - ca hai cung la ma 413 nhung la 2 enum
        // constant khac nhau, phai dung dung constant khong-deprecated.
        return buildResponse(
                HttpStatus.CONTENT_TOO_LARGE,
                "File tải lên vượt quá dung lượng cho phép (tối đa 25MB)"
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {

        log.error("Unexpected server error", e);

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Đã có lỗi xảy ra. Vui lòng thử lại sau."
        );
    }

    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status,
            String message) {

        return buildResponse(status, status.name(), message, null);
    }

    /*
     * FE-BE CONTRACT FIX:
     *
     * Truoc day key nay ten la "error" va chua gia tri status.getReasonPhrase()
     * (vi du "Bad Request", co khoang trang, khong on dinh cho logic).
     * FE (ApiErrorBody trong api/types.ts, ApiError trong api/client.ts)
     * lai doc field "code" - dan den error.code o FE LUON LA undefined.
     *
     * Doi ten key thanh "code" va dung status.name() (vi du "BAD_REQUEST",
     * "VALIDATION_ERROR"...) - dang SCREAMING_SNAKE_CASE on dinh, thich
     * hop de FE so sanh bang == trong tuong lai neu can (vi du hien thi
     * thong bao rieng cho "RATE_LIMIT_EXCEEDED"), thay vi parse chuoi
     * tieng Anh co khoang trang.
     */
    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status,
            String code,
            String message,
            Map<String, String> fieldErrors) {

        Map<String, Object> body = new LinkedHashMap<>();

        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);

        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            body.put("fieldErrors", fieldErrors);
        }

        return ResponseEntity
                .status(status)
                .body(body);
    }
}