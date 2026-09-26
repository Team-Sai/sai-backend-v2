package org.teamsai.saibackend.domain.contract.entity;


import jakarta.persistence.*;
import lombok.*;
import org.teamsai.saibackend.domain.contract.exception.ContractChangeErrorCode;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "loan_contract_change_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanContractChangeRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "change_request_id")
    private Long changeRequestId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "change_reason", columnDefinition = "TEXT")
    private String changeReason;

    @Column(name = "new_maturity_date")
    private LocalDate newMaturityDate;

    @Column(name = "new_interest_rate", precision = 5, scale = 2)
    private BigDecimal newInterestRate;

    @Column(name = "new_repayment_type", length = 30)
    private String newRepaymentType;

    @Column(name = "new_repayment_date")
    private Integer newRepaymentDate;

    @Column(name = "new_terms", columnDefinition = "TEXT")
    private String newTerms;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChangeRequestStatus status;

    @Column(name = "return_reason", columnDefinition = "TEXT")
    private String returnReason;

    @Column(name = "requester_signature", length = 255)
    private String requesterSignature;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void approve() {
        this.status = ChangeRequestStatus.APPROVED;
        this.updatedAt = LocalDateTime.now();
    }

    public void reject(String returnReason) {
        this.status = ChangeRequestStatus.REJECTED;
        this.returnReason = returnReason;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = ChangeRequestStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }

    public void attachRequesterSignature(String requesterSignature) {
        this.requesterSignature = requesterSignature;
        this.updatedAt = LocalDateTime.now();
    }

    public void validateBelongsTo(Long contractId) {
        if (!this.contractId.equals(contractId)) {
            throw ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND.toException();
        }
    }

    public void validateRequestedBy(Long userId) {
        if (!this.userId.equals(userId)) {
            throw ContractChangeErrorCode.NOT_CONTRACT_PARTY.toException();
        }
    }

    public void validatePending() {
        if (this.status != ChangeRequestStatus.PENDING) {
            throw ContractChangeErrorCode.ALREADY_BEING_REQUEST.toException();
        }
    }

    public void validateNotSigned() {
        if (this.requesterSignature != null) {
            throw ContractChangeErrorCode.ALREADY_SIGNED.toException();
        }
    }

    public LoanContractChangeRequestEntity(
            Long contractId,
            Long userId,
            String changeReason,
            LocalDate newMaturityDate,
            BigDecimal newInterestRate,
            String newRepaymentType,
            Integer newRepaymentDate,
            String newTerms,
            ChangeRequestStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this.contractId = contractId;
        this.userId = userId;
        this.changeReason = changeReason;
        this.newMaturityDate = newMaturityDate;
        this.newInterestRate = newInterestRate;
        this.newRepaymentType = newRepaymentType;
        this.newRepaymentDate = newRepaymentDate;
        this.newTerms = newTerms;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
