package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatPreviewRequest {

    @NotNull(message = "Vui lòng chọn connection")
    private Long connectionId;

    private Long conversationId;

    @NotBlank(message = "Vui lòng nhập câu hỏi")
    @Size(max = 2000, message = "Câu hỏi không được vượt quá 2000 ký tự")
    private String question;
}
