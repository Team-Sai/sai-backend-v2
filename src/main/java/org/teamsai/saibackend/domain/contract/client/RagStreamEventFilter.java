package org.teamsai.saibackend.domain.contract.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;

/**
 * AI 서버가 보낸 SSE 이벤트 중 프론트에 보내도 되는 것만 남긴다.
 *
 * <p>기존 API는 응답을 DTO(ContractClauseResponse)로 받아서
 * 모르는 필드(debug 등)가 자연스럽게 버려졌다.
 * 스트리밍은 받은 글을 그대로 중계하므로, 여기서 같은 역할을 한다.
 *
 * <ul>
 *   <li>모르는 이벤트 이름은 통째로 버린다.</li>
 *   <li>draft/final은 기존 API와 같은 DTO(ContractClauseResponse)를 한 번 거쳐서,
 *       references·validation 같은 <b>중첩 객체 안의</b> 모르는 필드까지 버린다.</li>
 *   <li>그다음 이벤트별로 정해진 최상위 필드만 남긴다.</li>
 * </ul>
 */
public final class RagStreamEventFilter {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 이 이벤트가 오면 스트림이 끝난다. 이게 버려지면 화면이 "검토 중"에서 멈추므로 따로 다룬다. */
    private static final Set<String> TERMINAL_EVENTS = Set.of("final", "error");

    /** DTO를 거쳐 중첩 필드까지 거르는 이벤트 */
    private static final Set<String> DTO_EVENTS = Set.of("draft", "final");

    /** 이벤트별로 프론트에 보낼 수 있는 최상위 필드 */
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

    /** final·error처럼 스트림을 끝내는 이벤트인지 */
    public static boolean isTerminal(String event) {
        return TERMINAL_EVENTS.contains(event);
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

        JsonNode source = body;

        if (DTO_EVENTS.contains(event)) {
            try {
                // 기존 API와 같은 DTO로 한 번 바꿨다가 다시 JSON으로: DTO에 없는 필드는 어느 깊이든 사라진다.
                ContractClauseResponse dto = MAPPER.treeToValue(body, ContractClauseResponse.class);
                source = MAPPER.valueToTree(dto);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        ObjectNode filtered = MAPPER.createObjectNode();

        for (String field : allowed) {
            // AI가 보내지 않은 필드는 DTO에서 null로 생기므로, 원래 있던 필드만 옮긴다.
            if (!body.has(field)) {
                continue;
            }

            // clauseRevised는 스트리밍에만 있는 값이라 DTO에 없다 → 원본에서 true/false만 옮긴다.
            JsonNode value = "clauseRevised".equals(field)
                    ? (body.get(field).isBoolean() ? body.get(field) : null)
                    : source.get(field);

            if (value != null) {
                filtered.set(field, value);
            }
        }

        return Optional.of(MAPPER.writeValueAsString(filtered));
    }
}