package org.teamsai.saibackend.domain.contract.repository;

import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface RepaymentScheduleWithRemainingProjection {

    Long getScheduleId();
    Long getContractId();
    Integer getSequence();
    LocalDate getDueDate();
    BigDecimal getPrincipalDue();
    BigDecimal getInterestDue();
    BigDecimal getTotalPaymentDue();
    BigDecimal getRemainingPaymentAmount();
    BigDecimal getRemainingPrincipal();
    RepaymentScheduleStatus getStatus();
    LocalDateTime getPaidAt();
    LocalDateTime getCreatedAt();
}
