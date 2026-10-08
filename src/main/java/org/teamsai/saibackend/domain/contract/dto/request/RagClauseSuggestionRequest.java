package org.teamsai.saibackend.domain.contract.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagClauseSuggestionRequest {

    private ContractContext contract;

    private String message;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ContractContext {

        private BigDecimal principal;

        private BigDecimal interestRate;

        private BigDecimal lateInterestRate;

        private String startDate;

        private String repaymentDate;

        private Integer repaymentDay;

        private String repaymentType;

        private String contractAlias;

        private String terms;
    }
}