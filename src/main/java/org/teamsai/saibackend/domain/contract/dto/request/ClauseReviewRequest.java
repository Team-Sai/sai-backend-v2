package org.teamsai.saibackend.domain.contract.dto.request;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 반영한 AI 조항 다시 검토 요청.
 *
 * request: 조항을 만들 때 보낸 계약 정보와 원래 요청
 * issues: 그때 받은 쟁점 (type만 있어도 된다)
 * clause: 사용자가 [입력 필요]를 채우거나 고친 조항 문장
 */
public record ClauseReviewRequest(
        @NotNull(message = "계약 정보가 필요합니다.")
        @Valid
        ContractClauseRequest request,

        @NotEmpty(message = "쟁점 정보가 필요합니다.")
        List<Map<String, Object>> issues,

        @NotBlank(message = "검토할 조항 문장을 입력해 주세요.")
        @Size(max = 5000, message = "조항은 5,000자 이내로 입력해 주세요.")
        String clause
) {
}