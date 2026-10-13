
package org.teamsai.saibackend.domain.contract.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContractClauseResponse {

    private String message;

    private List<Issue> issues;

    private String title;

    private String summary;

    private String suggestedClause;

    private List<String> warnings;

    private Validation validation;

    private List<Reference> references;

    private List<UnresolvedDecision> unresolvedDecisions;

    // 기존 생성자 호출과의 호환성 유지
    public ContractClauseResponse(
            String message,
            List<Issue> issues,
            String title,
            String summary,
            String suggestedClause,
            List<String> warnings,
            Validation validation,
            List<Reference> references
    ) {
        this(
                message,
                issues,
                title,
                summary,
                suggestedClause,
                warnings,
                validation,
                references,
                null
        );
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Issue {

        private String type;

        private String reason;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Validation {

        private String status;

        private Boolean isSafeToSuggest;

        private List<ValidationCheck> checks;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValidationCheck {

        private String type;

        private String status;

        private String message;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Reference {

        private String documentId;

        private String lawName;

        private String articleNumber;

        private String articleTitle;

        /**
         * RAG가 실제로 검색한 법령 조문 원문
         */
        private String content;

        private Double vectorScore;

        private Double rerankScore;

        private Double finalScore;

        private String matchedIssue;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UnresolvedDecision {

        private String name;

        private String reason;
    }
}
