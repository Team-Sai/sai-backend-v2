package org.teamsai.saibackend.domain.contract.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 서버가 보낸 SSE 이벤트 중 프론트에 보내도 되는 것만 남긴다.
 *
 * <p>기존 API는 응답을 DTO(ContractClauseResponse)로 받아서
 * 모르는 필드(debug 등)가 자연스럽게 버려졌다.
 * 스트리밍은 받은 글을 그대로 중계하므로, 여기서 같은 역할을 한다.
 *
 * <ul>
 *   <li>모르는 이벤트 이름은 통째로 버린다.</li>
 *   <li>아는 이벤트도 정해진 필드만 남긴다. (맨 위 단계 기준)</li>
 * </ul>
 */
public final class RagStreamEventFilter {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 이벤트별로 프론트에 보낼 수 있는 필드 */
    private static final Map<String, List<String>> ALLOWED_FIELDS = Map.of(
            "progress", List.of("step"),
            "delta", List.of("text"),
            "draft", List.of(
                    "title",
                    "summary",
                    "suggestedClause",
                    "warnings",
                    "unresolvedDecisions",
                    "references"
            ),
            "final", List.of(
                    "message",
                    "issues",
                    "title",
                    "summary",
                    "suggestedClause",
                    "warnings",
                    "validation",
                    "references",
                    "unresolvedDecisions",
                    "clauseRevised"
            ),
            "error", List.of("status", "message")
    );

    private RagStreamEventFilter() {
    }

    /**
     * @return 보내도 되는 데이터(JSON 문자열). 보내면 안 되는 이벤트면 빈 값.
     */
    public static Optional<String> filter(String event, String data) {
        List<String> allowed = ALLOWED_FIELDS.get(event);

        if (allowed == null) {
            return Optional.empty();
        }

        JsonNode body;

        try {
            body = MAPPER.readTree(data);
        } catch (RuntimeException e) {
            // JSON이 아니면 내용을 알 수 없으므로 보내지 않는다.
            return Optional.empty();
        }

        if (body == null || !body.isObject()) {
            return Optional.empty();
        }

        ObjectNode filtered = MAPPER.createObjectNode();

        for (String field : allowed) {
            JsonNode value = body.get(field);

            if (value != null) {
                filtered.set(field, value);
            }
        }

        return Optional.of(MAPPER.writeValueAsString(filtered));
    }
}