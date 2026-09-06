package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ChangePasswordRequest;
import com.example.aidatabaseassistant.dto.UpdateProfileRequest;
import com.example.aidatabaseassistant.dto.UserProfileResponse;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Lấy thông tin profile của user đang đăng nhập.
     *
     * @param username username lấy từ JWT / Authentication
     * @return thông tin profile, không bao giờ trả passwordHash
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUser(String username) {

        User user = findUserByUsername(username);

        return toProfileResponse(user);
    }

    /**
     * Cập nhật thông tin profile của user đang đăng nhập.
     *
     * Hiện tại cho phép cập nhật email.
     * Không cho client tự sửa:
     * - id
     * - username
     * - passwordHash
     * - role
     * - locked
     *
     * @param username username lấy từ JWT / Authentication
     * @param request dữ liệu profile mới
     * @return profile sau khi cập nhật
     */
    @Transactional
    public UserProfileResponse updateCurrentUser(
            String username,
            UpdateProfileRequest request) {

        User user = findUserByUsername(username);

        String newEmail = request.getEmail().trim();

        /*
         * Nếu email mới khác email hiện tại thì phải kiểm tra
         * email đó chưa thuộc về một user khác.
         */
        if (!user.getEmail().equalsIgnoreCase(newEmail)
                && userRepository.existsByEmail(newEmail)) {

            throw new IllegalArgumentException("Email đã tồn tại");
        }

        user.setEmail(newEmail);

        User savedUser = userRepository.save(user);

        return toProfileResponse(savedUser);
    }

    /**
     * Đổi mật khẩu cho user đang đăng nhập.
     *
     * Quy trình:
     * 1. Tìm user theo username từ JWT.
     * 2. Kiểm tra mật khẩu hiện tại bằng PasswordEncoder.matches().
     * 3. Encode mật khẩu mới bằng PasswordEncoder.
     * 4. Lưu passwordHash mới.
     *
     * @param username username lấy từ JWT / Authentication
     * @param request currentPassword + newPassword
     */
    @Transactional
    public void changePassword(
            String username,
            ChangePasswordRequest request) {

        User user = findUserByUsername(username);

        /*
         * Tuyệt đối không so sánh password plaintext trực tiếp.
         *
         * PasswordEncoder.matches(rawPassword, encodedPassword)
         * sẽ kiểm tra password hiện tại với BCrypt hash đang lưu.
         */
        if (!passwordEncoder.matches(
                request.getCurrentPassword(),
                user.getPasswordHash())) {

            throw new IllegalArgumentException(
                    "Mật khẩu hiện tại không chính xác"
            );
        }

        /*
         * Không cho đổi sang đúng mật khẩu cũ.
         * Đây là kiểm tra UX/security bổ sung.
         */
        if (passwordEncoder.matches(
                request.getNewPassword(),
                user.getPasswordHash())) {

            throw new IllegalArgumentException(
                    "Mật khẩu mới phải khác mật khẩu hiện tại"
            );
        }

        /*
         * Luôn encode password mới trước khi lưu DB.
         */
        String newPasswordHash =
                passwordEncoder.encode(request.getNewPassword());

        user.setPasswordHash(newPasswordHash);

        userRepository.save(user);
    }

    /**
     * Tìm user theo username.
     *
     * Không nhận userId từ request.
     * Username được lấy từ Authentication nên user chỉ có thể
     * thao tác với chính tài khoản đang đăng nhập.
     */
    private User findUserByUsername(String username) {

        return userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản"
                        )
                );
    }

    /**
     * Chuyển User entity thành DTO trả về FE.
     *
     * CỰC KỲ QUAN TRỌNG:
     * Không trả passwordHash ra API.
     */
    private UserProfileResponse toProfileResponse(User user) {

        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole().name()
        );
    }
}