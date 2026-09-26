package org.teamsai.saibackend.domain.batch.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;

@Service
@RequiredArgsConstructor
public class ReminderNotificationSender {

    private final LoanContractService loanContractService;
    private final NotificationService notificationService;

    public enum Outcome {
        PROCESSED,
        SKIPPED
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome sendOne(
            Long contractId,
            Long scheduleId,
            NotificationType type,
            String title,
            String content
    ) {
        Long debtorUserId = loanContractService.findDebtorUserId(contractId);

        if (debtorUserId == null) {
            return Outcome.SKIPPED;
        }

        notificationService.createIfAbsent(
                debtorUserId,
                type,
                title,
                content,
                scheduleId,
                null
        );

        return Outcome.PROCESSED;
    }
}
