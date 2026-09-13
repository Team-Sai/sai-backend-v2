package org.teamsai.saibackend.domain.contract.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.teamsai.saibackend.domain.contract.dto.request.ContractRelationType;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.request.RepaymentMethod;
import org.teamsai.saibackend.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name= "loan_contract")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="contract_id")
    private Long contractId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_contract_id")
    private LoanContract previousContract;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", length = 20, nullable = false)
    @ColumnDefault("ACQUAINTANCE")
    private ContractRelationType relationType;

    @Column(name="principal_amount", nullable = false, precision=15, scale=2)
    private BigDecimal principalAmount;

    @Column(name="interest_rate", nullable = false, precision=5, scale=2)
    private BigDecimal interestRate;

    @Enumerated(EnumType.STRING)
    @Column(name="repayment_type",length=30,nullable = false)
    private RepaymentMethod repaymentType;

    @Column(name="start_date", nullable = false)
    private LocalDate startDate;
    @Column(name="maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name="repayment_day",nullable = false)
    private Integer repaymentDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @ColumnDefault("DRAFT")
    private ContractStatus status;

    @Column(name="creditor_address",nullable = false)
    private String creditorAddress;
    @Column(name="debtor_address")
    private String debtorAddress;
    @Column(name="contract_alias",nullable = false)
    private String contractAlias;
    @Column(name="terms")
    private String terms;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creditor_id")
    private User creditor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debtor_id")
    private User debtor;

    @Column(name="creditor_signature")
    private String creditorSignature;
    @Column(name="debtor_signature")
    private String debtorSignature;

    @CreationTimestamp
    @Column(name="created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp
    @Column(name="updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public LoanContract(LoanContract previousContract, ContractRelationType relationType,
                        BigDecimal principalAmount, BigDecimal interestRate, RepaymentMethod repaymentType,
                        LocalDate startDate, LocalDate maturityDate, Integer repaymentDay, ContractStatus status,
                        String creditorAddress, String debtorAddress, String contractAlias, String terms,
                        User creditor, User debtor, String creditorSignature, String debtorSignature) {
        this.previousContract = previousContract;
        this.relationType = (relationType != null) ? relationType : ContractRelationType.ACQUAINTANCE;
        this.principalAmount = principalAmount;
        this.interestRate = interestRate;
        this.repaymentType = repaymentType;
        this.startDate = startDate;
        this.maturityDate = maturityDate;
        this.repaymentDay = repaymentDay;
        this.status = (status != null) ? status : ContractStatus.DRAFT;
        this.creditorAddress = creditorAddress;
        this.debtorAddress = debtorAddress;
        this.contractAlias = contractAlias;
        this.terms = terms;
        this.creditor = creditor;
        this.debtor = debtor;
        this.creditorSignature = creditorSignature;
        this.debtorSignature = debtorSignature;
    }

    public void linkDebtor(User debtor) {
        this.debtor = debtor;
    }

    public void submitCreditorSignature(String creditorSignature, User debtor, ContractStatus status) {
        this.creditorSignature = creditorSignature;
        this.debtor = debtor;
        this.status = status;
    }

    public void submitDebtorSignature(String debtorAddress, String debtorSignature, ContractStatus status) {
        this.debtorAddress = debtorAddress;
        this.debtorSignature = debtorSignature;
        this.status = status;
    }

    public void changeStatus(ContractStatus status) {
        this.status = status;
    }

    public boolean signCreditorIfUnsigned(String creditorSignature, ContractStatus status) {
        if (this.creditorSignature != null) {
            return false;
        }
        this.creditorSignature = creditorSignature;
        this.status = status;
        return true;
    }

    public boolean signDebtorIfUnsigned(String debtorSignature, ContractStatus status) {
        if (this.debtorSignature != null) {
            return false;
        }
        this.debtorSignature = debtorSignature;
        this.status = status;
        return true;
    }







}
