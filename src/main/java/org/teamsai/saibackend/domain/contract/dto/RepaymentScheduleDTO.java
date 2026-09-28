package org.teamsai.saibackend.domain.contract.dto;

import lombok.Builder;
import lombok.Getter;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;

@Getter
@Builder
public class RepaymentScheduleDTO {

    private Long scheduleId;
    private BigDecimal totalPaymentDue;
    private RepaymentScheduleStatus status;

    public static RepaymentScheduleDTO from(RepaymentSchedule entity) {
        return RepaymentScheduleDTO.builder()
                .scheduleId(entity.getScheduleId())
                .totalPaymentDue(entity.getTotalPaymentDue())
                .status(entity.getStatus())
                .build();
    }
}