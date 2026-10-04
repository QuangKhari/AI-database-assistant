package com.example.aidatabaseassistant.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Gui email that qua SMTP (Spring Mail / Jakarta Mail).
 *
 * Hien tai chi dung cho 1 tinh nang: email dat lai mat khau
 * (xem AuthService.forgotPassword()).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:}")
    private String fromAddress;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    /**
     * Gui email chua lien ket dat lai mat khau.
     *
     * QUAN TRONG - KHONG BAO GIO nem exception ra ngoai method nay.
     *
     * AuthService.forgotPassword() phai luon tra ve CUNG MOT ket qua
     * thanh cong bat ke email co ton tai hay khong (chong user
     * enumeration - xem comment trong AuthService). Neu de loi SMTP
     * (sai cau hinh, mat mang, Gmail tu choi...) vang ra ngoai, request
     * se bi GlobalExceptionHandler bat thanh 1 loi 500 khac han voi
     * response 200 binh thuong - vo tinh lo ra cho ke tan cong biet
     * "email nay ton tai that (vi backend co chay logic gui mail) nhung
     * server dang loi", khac voi truong hop email khong ton tai (luon
     * im lang, khong lam gi). Vi vay moi loi o day chi duoc log lai,
     * khong duoc throw tiep.
     */
    public void sendPasswordResetEmail(
            String toEmail,
            String username,
            String rawToken,
            int expirationMinutes
    ) {

        String resetLink = frontendBaseUrl + "/reset-password?token=" + rawToken;
        String effectiveFrom = resolveFromAddress();

        try {
            MimeMessage message = mailSender.createMimeMessage();

            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(effectiveFrom);
            helper.setTo(toEmail);
            helper.setSubject("Đặt lại mật khẩu - AI Database Assistant");
            helper.setText(
                    buildHtmlBody(username, resetLink, expirationMinutes),
                    true // isHtml
            );

            mailSender.send(message);

            log.info("Đã gửi email đặt lại mật khẩu cho user={}", username);

        } catch (MessagingException | MailException e) {
            // Nuot loi co chu dich - xem Javadoc phia tren.
            log.error(
                    "Gửi email đặt lại mật khẩu thất bại cho user={}: {}",
                    username,
                    e.getMessage()
            );
        }
    }

    /**
     * app.mail.from co the ton tai nhung RONG (vi du bien moi truong MAIL_FROM
     * duoc Docker Compose dua vao container voi gia tri chuoi rong thay vi
     * hoan toan khong ton tai) - khi do Spring KHONG fallback ve gia tri mac
     * dinh nhu mong doi, vi placeholder resolver coi "key ton tai (du rong)"
     * khac voi "key khong ton tai". Xu ly du phong nay ngay trong code de
     * khong phu thuoc vao cach bien moi truong duoc set/khong set o tung
     * moi truong (local, Docker, production).
     */
    private String resolveFromAddress() {
        if (fromAddress != null && !fromAddress.isBlank()) {
            return fromAddress;
        }
        return mailUsername;
    }

    private String buildHtmlBody(
            String username,
            String resetLink,
            int expirationMinutes
    ) {

        // Escape username truoc khi nhung vao HTML - username khong bi
        // gioi han ky tu dac biet o tang validation (RegisterRequest chi
        // gioi han do dai), nen phong truong hop username chua ky tu HTML.
        String safeUsername = escapeHtml(username);

        return """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:0 auto;color:#1f2937;">
                  <h2 style="color:#111827;">Đặt lại mật khẩu</h2>
                  <p>Xin chào <strong>%s</strong>,</p>
                  <p>Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn trên <strong>AI Database Assistant</strong>.</p>
                  <p style="text-align:center;margin:28px 0;">
                    <a href="%s" style="background:#2563eb;color:#ffffff;padding:12px 28px;border-radius:6px;text-decoration:none;font-weight:bold;display:inline-block;">
                      Đặt lại mật khẩu
                    </a>
                  </p>
                  <p>Liên kết này sẽ hết hạn sau <strong>%d phút</strong> và chỉ dùng được <strong>một lần</strong>.</p>
                  <p>Nếu bạn không yêu cầu điều này, vui lòng bỏ qua email này - mật khẩu của bạn sẽ không bị thay đổi.</p>
                  <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0;" />
                  <p style="color:#6b7280;font-size:12px;">
                    Nếu nút bấm phía trên không hoạt động, hãy sao chép và dán liên kết sau vào trình duyệt:<br/>
                    <span style="word-break:break-all;">%s</span>
                  </p>
                </div>
                """.formatted(safeUsername, resetLink, expirationMinutes, resetLink);
    }

    private String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}