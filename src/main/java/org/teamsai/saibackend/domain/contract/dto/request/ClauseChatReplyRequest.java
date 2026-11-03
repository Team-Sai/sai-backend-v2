package org.teamsai.saibackend.domain.contract.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 대화형 특약 답장 요청. 버튼을 눌러도 버튼 글자가 그대로 온다.
 */
public record ClauseChatReplyRequest(

        @NotBlank(message = "답장 내용을 입력해 주세요.")
        @Size(max = 2000, message = "답장은 2000자 이하로 입력해 주세요.")
        String message
) {
}