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
public class ContractLegalReviewResponse {

    private Review review;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Review {

        private Summary summary;

        private List<ReviewItem> items;

        private String disclaimer;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Summary {

        private Integer total;

        private Integer passed;

        private Integer warning;

        private Integer error;

        private Integer review;

        private String message;

        private String description;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewItem {

        private String field;

        private String type;

        private String status;

        private String title;

        private String summary;

        private String detail;

        private String suggestion;

        private List<Reference> references;
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

        private Double score;
    }
}
