package org.teamsai.saibackend.domain.contract.dto.request;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 서버 다시 검토 요청 (/rag/contracts/clause-review).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagClauseReviewRequest {

    private RagClauseSuggestionRequest.ContractContext contract;

    private String message;

    private List<Map<String, Object>> issues;

    private String clause;
}