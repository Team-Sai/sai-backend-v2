package org.teamsai.saibackend.domain.contract.dto.response;

import java.util.List;
import java.util.Map;

/**
 * 대화형 특약 답장 응답.
 * kind: ANSWER / VALUE_CHANGE / UNKNOWN / CONFIRM / REJECT / AMBIGUOUS
 */
public record ClauseChatReplyResponse(
        String kind,
        String echo,
        List<Map<String, Object>> slots,
        List<Map<String, Object>> pendingQuestions,
        boolean readyToWrite
) {
}