package org.teamsai.saibackend.domain.batch.settlement.abandonment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.global.notification.SlackNotifier;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class SettlementAbandonmentNotifier {
    private final SlackNotifier slackNotifier;

    public void notifyAbandoned(Settlement settlement, LocalDate referenceDate, LocalDate baseDate) {
        slackNotifier.send(String.format(
                "⚠️ *정산 장기 방치 감지* — settlementId=%d, title=%s, 기준일=%s (%d일 경과), 여전히 IN_PROGRESS",
                settlement.getSettlementId(), settlement.getTitle(), referenceDate,
                ChronoUnit.DAYS.between(referenceDate, baseDate)));
    }
}
