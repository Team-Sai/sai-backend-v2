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

        private Double vectorScore;

        private Double rerankScore;

        private Double finalScore;

        private String matchedIssue;
    }
}