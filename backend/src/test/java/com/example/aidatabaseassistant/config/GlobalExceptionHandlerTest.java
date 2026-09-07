package com.example.aidatabaseassistant.config;

import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.HandlerMethod;
import jakarta.servlet.http.HttpServletRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private HttpServletRequest request;

    @BeforeEach
    void setUpRequest() {
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        assertNotNull(response.getBody());
        return response.getBody();
    }

    // Helper method dung lam "target method" gia de tao MethodParameter -
    // MethodArgumentNotValidException bat buoc phai co MethodParameter that.
    @SuppressWarnings("unused")
    private void dummyValidatedMethod(Object arg) {
    }

    private MethodArgumentNotValidException buildValidationException(
            FieldError... fieldErrors) throws NoSuchMethodException {

        Method method = getClass().getDeclaredMethod(
                "dummyValidatedMethod",
                Object.class
        );

        HandlerMethod handlerMethod = new HandlerMethod(this, method);

        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(
                        new Object(),
                        "request"
                );

        for (FieldError fe : fieldErrors) {
            bindingResult.addError(fe);
        }

        return new MethodArgumentNotValidException(
                handlerMethod.getMethodParameters()[0],
                bindingResult
        );
    }

    // ===================== code thay vi error =====================

    @Test
    void handleIllegalArgument_shouldReturnCodeBadRequest_notErrorKey() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleIllegalArgument(
                        new IllegalArgumentException("SQL không hợp lệ"),
                        request
                );

        Map<String, Object> body = body(response);

        assertEquals(
                HttpStatus.BAD_REQUEST.value(),
                body.get("status")
        );

        assertEquals(
                "BAD_REQUEST",
                body.get("code")
        );

        assertEquals(
                "SQL không hợp lệ",
                body.get("message")
        );

        // Key "error" cua hanh vi CU khong duoc phep con ton tai nua.
        assertFalse(
                body.containsKey("error"),
                "Response không được còn field 'error' cũ"
        );
    }

    @Test
    void handleBadCredentials_shouldReturnCodeUnauthorized() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleBadCredentials(
                        new BadCredentialsException("bad creds"),
                        request
                );

        Map<String, Object> body = body(response);

        assertEquals(
                "UNAUTHORIZED",
                body.get("code")
        );

        assertFalse(body.containsKey("error"));
    }

    @Test
    void handleLockedAccount_shouldReturnCodeUnauthorized() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleLockedAccount(
                        new LockedException("locked"),
                        request
                );

        Map<String, Object> body = body(response);

        assertEquals(
                "UNAUTHORIZED",
                body.get("code")
        );

        assertEquals(
                "Tài khoản đã bị khóa",
                body.get("message")
        );
    }

    @Test
    void handleRateLimit_shouldReturnCodeTooManyRequests() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleRateLimit(
                        new RateLimitExceededException("Quá nhiều yêu cầu"),
                        request
                );

        Map<String, Object> body = body(response);

        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                body.get("status")
        );

        // Production contract hiện tại:
        assertEquals(
                "RATE_LIMIT_EXCEEDED",
                body.get("code")
        );

        assertEquals(
                "Quá nhiều yêu cầu",
                body.get("message")
        );
    }

    @Test
    void handleGeneral_shouldReturnCodeInternalServerError() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleGeneral(
                        new RuntimeException("boom"),
                        request
                );

        Map<String, Object> body = body(response);

        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                body.get("status")
        );

        // Production contract hiện tại:
        assertEquals(
                "INTERNAL_ERROR",
                body.get("code")
        );
    }

    // ===================== fieldErrors cho validation =====================

    @Test
    void handleValidation_withSingleFieldError_shouldReturnFieldErrorsMap()
            throws Exception {

        MethodArgumentNotValidException ex =
                buildValidationException(
                        new FieldError(
                                "registerRequest",
                                "username",
                                "Tên đăng nhập không được để trống"
                        )
                );

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidation(ex, request);

        Map<String, Object> body = body(response);

        assertEquals(
                HttpStatus.BAD_REQUEST.value(),
                body.get("status")
        );

        assertEquals(
                "VALIDATION_ERROR",
                body.get("code")
        );

        assertEquals(
                "Tên đăng nhập không được để trống",
                body.get("message")
        );

        assertTrue(body.containsKey("fieldErrors"));

        @SuppressWarnings("unchecked")
        Map<String, String> fieldErrors =
                (Map<String, String>) body.get("fieldErrors");

        assertEquals(
                "Tên đăng nhập không được để trống",
                fieldErrors.get("username")
        );
    }

    @Test
    void handleValidation_withMultipleFieldErrors_shouldIncludeAllFieldsInMap()
            throws Exception {

        MethodArgumentNotValidException ex =
                buildValidationException(
                        new FieldError(
                                "registerRequest",
                                "username",
                                "Tên đăng nhập không được để trống"
                        ),
                        new FieldError(
                                "registerRequest",
                                "email",
                                "Email không hợp lệ"
                        ),
                        new FieldError(
                                "registerRequest",
                                "password",
                                "Mật khẩu phải có ít nhất 6 ký tự"
                        )
                );

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidation(ex, request);

        Map<String, Object> body = body(response);

        @SuppressWarnings("unchecked")
        Map<String, String> fieldErrors =
                (Map<String, String>) body.get("fieldErrors");

        assertEquals(3, fieldErrors.size());

        assertEquals(
                "Tên đăng nhập không được để trống",
                fieldErrors.get("username")
        );

        assertEquals(
                "Email không hợp lệ",
                fieldErrors.get("email")
        );

        assertEquals(
                "Mật khẩu phải có ít nhất 6 ký tự",
                fieldErrors.get("password")
        );
    }

    @Test
    void handleValidation_withMultipleErrorsOnSameField_shouldKeepFirstOnly()
            throws Exception {

        MethodArgumentNotValidException ex =
                buildValidationException(
                        new FieldError(
                                "registerRequest",
                                "password",
                                "Mật khẩu không được để trống"
                        ),
                        new FieldError(
                                "registerRequest",
                                "password",
                                "Mật khẩu phải có ít nhất 6 ký tự"
                        )
                );

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidation(ex, request);

        Map<String, Object> body = body(response);

        @SuppressWarnings("unchecked")
        Map<String, String> fieldErrors =
                (Map<String, String>) body.get("fieldErrors");

        assertEquals(1, fieldErrors.size());

        assertEquals(
                "Mật khẩu không được để trống",
                fieldErrors.get("password")
        );
    }

    @Test
    void handleValidation_withNoFieldErrors_shouldOmitFieldErrorsKey()
            throws Exception {

        MethodArgumentNotValidException ex =
                buildValidationException();

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidation(ex, request);

        Map<String, Object> body = body(response);

        assertEquals(
                "Dữ liệu không hợp lệ",
                body.get("message")
        );

        assertFalse(
                body.containsKey("fieldErrors")
        );
    }

    @Test
    void handleValidation_withNullDefaultMessage_shouldFallBackToGenericText()
            throws Exception {

        FieldError fieldErrorWithNullMessage =
                new FieldError(
                        "registerRequest",
                        "email",
                        null,
                        false,
                        null,
                        null,
                        null
                );

        MethodArgumentNotValidException ex =
                buildValidationException(
                        fieldErrorWithNullMessage
                );

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidation(ex, request);

        Map<String, Object> body = body(response);

        @SuppressWarnings("unchecked")
        Map<String, String> fieldErrors =
                (Map<String, String>) body.get("fieldErrors");

        assertEquals(
                "Giá trị không hợp lệ",
                fieldErrors.get("email")
        );
    }
}