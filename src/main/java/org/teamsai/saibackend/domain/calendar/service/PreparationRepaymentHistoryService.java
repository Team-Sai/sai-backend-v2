package org.teamsai.saibackend.domain.calendar.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationRepaymentHistory;
import org.teamsai.saibackend.domain.calendar.exception.PreparationEventErrorCode;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@Transactional(readOnly = true)
public class PreparationRepaymentHistoryService {

    private static final ZoneId ZONE =
            ZoneId.of("Asia/Seoul");

    private final PaymentRecordRepository paymentRecordRepository;
    private final Clock clock;

    public PreparationRepaymentHistoryService(
            PaymentRecordRepository paymentRecordRepository,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.paymentRecordRepository = paymentRecordRepository;
        this.clock = clock;
    }

    public PreparationRepaymentHistory read(Long userId) {
        if (userId == null) {
            throw PreparationEventErrorCode.UNAUTHENTICATED.toException();
        }

        LocalDateTime toExclusive =
                LocalDateTime.ofInstant(clock.instant(), ZONE);

        LocalDateTime fromInclusive =
                toExclusive.minusMonths(3);

        var summary =
                paymentRecordRepository.summarizePreparationHistory(
                        userId,
                        fromInclusive,
                        toExclusive
                );

        return new PreparationRepaymentHistory(
                fromInclusive,
                toExclusive,
                summary == null
                        || summary.getConfirmedRecordCount() == null
                        ? 0L
                        : summary.getConfirmedRecordCount(),
                summary == null
                        || summary.getConfirmedRecordedAmount() == null
                        ? BigDecimal.ZERO
                        : summary.getConfirmedRecordedAmount(),
                summary == null
                        || summary.getRecordedScheduleCount() == null
                        ? 0L
                        : summary.getRecordedScheduleCount()
        );
    }
}