package org.teamsai.saibackend.domain.payment.repository;

import java.math.BigDecimal;

public interface PreparationRepaymentHistoryProjection {

    Long getConfirmedRecordCount();

    BigDecimal getConfirmedRecordedAmount();

    Long getRecordedScheduleCount();
}