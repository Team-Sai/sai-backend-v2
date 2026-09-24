package org.teamsai.saibackend.domain.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequestEntity;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanContractChangeDTO {

    private Long changeRequestId;
    private Long userId;
    private String changeReason;
    private LocalDate newMaturityDate;
    private BigDecimal newInterestRate;
    private String newRepaymentType;
    private Integer newRepaymentDate;
    private String newTerms;
    private ChangeRequestStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long contractId;
    private String returnReason;
    private String requesterSignature;

    public static LoanContractChangeDTO from(LoanContractChangeRequestEntity entity) {
        return LoanContractChangeDTO.builder()
                .changeRequestId(entity.getChangeRequestId())
                .contractId(entity.getContractId())
                .userId(entity.getUserId())
                .changeReason(entity.getChangeReason())
                .newMaturityDate(entity.getNewMaturityDate())
                .newInterestRate(entity.getNewInterestRate())
                .newRepaymentType(entity.getNewRepaymentType())
                .newRepaymentDate(entity.getNewRepaymentDate())
                .newTerms(entity.getNewTerms())
                .status(entity.getStatus())
                .returnReason(entity.getReturnReason())
                .requesterSignature(entity.getRequesterSignature())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}