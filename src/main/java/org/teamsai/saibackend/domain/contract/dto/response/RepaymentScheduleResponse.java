package org.teamsai.saibackend.domain.contract.dto.response;

import lombok.Builder;
import lombok.Getter;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class RepaymentScheduleResponse {
    private Long scheduleId;
    private Integer sequence;
    private LocalDate dueDate;
    private BigDecimal principalDue;
    private BigDecimal interestDue;
    private BigDecimal totalPaymentDue;
    private BigDecimal remainingPrincipal;
    private RepaymentScheduleStatus status;
    private LocalDateTime paidAt;
    private BigDecimal paidAmount;
    private boolean earlyPayoff;

    public static RepaymentScheduleResponse from(RepaymentSchedule entity, BigDecimal paidAmount, boolean earlyPayoff) {
        return RepaymentScheduleResponse.builder()
                .scheduleId(entity.getScheduleId())
                .sequence(entity.getSequence())
                .dueDate(entity.getDueDate())
                .principalDue(entity.getPrincipalDue())
                .interestDue(entity.getInterestDue())
                .totalPaymentDue(entity.getTotalPaymentDue())
                .remainingPrincipal(entity.getRemainingPrincipal())
                .status(entity.getStatus())
                .paidAt(entity.getPaidAt())
                .paidAmount(paidAmount)
                .earlyPayoff(earlyPayoff)
                .build();
    }
}
