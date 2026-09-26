package org.teamsai.saibackend.domain.contract.dto.response;

import lombok.Builder;
import lombok.Getter;
import org.teamsai.saibackend.domain.contract.entity.RepaymentScheduleEntity;
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

    public static RepaymentScheduleResponse from(RepaymentScheduleEntity entity) {
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
                .build();
    }
}
