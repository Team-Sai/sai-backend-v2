package org.teamsai.saibackend.domain.batch.settlement.abandonment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.settlement.repository.SettlementAbandonmentAlertRepository;
import org.teamsai.saibackend.domain.settlement.type.AbandonmentDeliveryStatus;
import org.teamsai.saibackend.global.notification.SlackNotifier;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class SettlementAbandonmentNotifier {
    private final SettlementAbandonmentAlertRepository alertRepository;
    private final SlackNotifier slackNotifier;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendPending(Long settlementId, LocalDate referenceDate) {
        // Serialize competing workers until delivery and the SENT transition finish.
        var alert = alertRepository.findForDelivery(settlementId, referenceDate).orElse(null);
        if (alert == null || alert.getDeliveryStatus() != AbandonmentDeliveryStatus.PENDING) {
            return false;
        }
        if (!slackNotifier.trySend(alert.getMessage())) {
            return false;
        }
        // At-least-once delivery: a crash after Slack accepts but before commit can replay the message.
        alert.markSent(LocalDateTime.now());
        return true;
    }
}
