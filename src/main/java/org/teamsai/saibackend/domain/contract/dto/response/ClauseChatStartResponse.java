package org.teamsai.saibackend.domain.contract.dto.response;

import java.util.List;
import java.util.Map;

/**
 * 대화형 특약 시작(이해 확인) 응답.
 *
 * 범위 밖 요청이거나 계약서 칸을 고치라는 안내면 대화를 만들지 않으므로 sessionId가 null이다.
 */
public record ClauseChatStartResponse(
        String sessionId,
        boolean inScope,
        String summary,
        List<Map<String, Object>> slots,
        List<Map<String, Object>> questions,
        List<Map<String, Object>> fieldEdits,
        String guide,
        List<String> notices
) {
}