package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ChangePasswordRequest;
import com.example.aidatabaseassistant.dto.OperationResponse;
import com.example.aidatabaseassistant.dto.UpdateProfileRequest;
import com.example.aidatabaseassistant.dto.UserProfileResponse;
import com.example.aidatabaseassistant.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * Lấy profile của user hiện tại.
     *
     * GET /api/users/me
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getProfile(
            Authentication authentication) {

        UserProfileResponse response =
                userService.getCurrentUser(authentication.getName());

        return ResponseEntity.ok(response);
    }

    /**
     * Cập nhật profile của user hiện tại.
     *
     * PUT /api/users/me
     */
    @PutMapping("/me")
    public ResponseEntity<UserProfileResponse> updateProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request) {

        UserProfileResponse response =
                userService.updateCurrentUser(
                        authentication.getName(),
                        request
                );

        return ResponseEntity.ok(response);
    }

    /**
     * Đổi mật khẩu của user hiện tại.
     *
     * PUT /api/users/me/password
     */
    @PutMapping("/me/password")
    public ResponseEntity<OperationResponse> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request) {

        userService.changePassword(
                authentication.getName(),
                request
        );

        return ResponseEntity.ok(
                new OperationResponse("Đổi mật khẩu thành công.")
        );
    }
}