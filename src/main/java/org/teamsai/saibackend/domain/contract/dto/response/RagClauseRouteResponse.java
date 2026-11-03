package org.teamsai.saibackend.domain.contract.dto.response;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 서버 답장 분류 응답 (/clause-chat/route).
 * kind: ANSWER / VALUE_CHANGE / UNKNOWN / CONFIRM / REJECT / AMBIGUOUS
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RagClauseRouteResponse {

    private String kind;

    private List<Map<String, Object>> slots;

    private Map<String, String> answers;

    private List<Map<String, Object>> pendingQuestions;

    private String echo;

    private Boolean readyToWrite;
}