package org.teamsai.saibackend.domain.contract.dto.response;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 반영한 AI 조항 다시 검토 결과.
 *
 * AI 응답을 이 모양으로 받아 그대로 돌려준다.
 * 여기 없는 칸(validatorMode 같은 내부 정보)은 받을 때 버려진다.
 *
 * suggestedClause: 검증에서 문장을 고쳤을 때만 값이 있다. (반영은 사용자가 고른다)
 * openSlots: 아직 채우지 않은 [입력 필요] 칸 이름
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClauseReviewResponse(
        String status,
        Boolean isSafeToSuggest,
        List<Map<String, Object>> checks,
        List<String> warnings,
        List<String> openSlots,
        String suggestedClause
) {
}