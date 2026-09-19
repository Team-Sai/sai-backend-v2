package org.teamsai.saibackend.domain.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.entity.RepaymentScheduleEntity;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepaymentDueReminderService {

    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final LoanContractRepository loanContractRepository;
    private final NotificationService notificationService;

    @Transactional
    public int sendDueReminders(LocalDate baseDate) {
        List<LocalDate> targetDates = List.of(
                baseDate.plusDays(3),
                baseDate.plusDays(1),
                baseDate
        );

        List<RepaymentScheduleEntity> schedules = repaymentScheduleRepository
                .findByDueDateInAndStatus(targetDates, RepaymentScheduleStatus.PENDING);

        int processed = 0;
        for (RepaymentScheduleEntity schedule : schedules) {
            NotificationStage stage = resolveStage(schedule.getDueDate(), baseDate);
            if (stage == null) {
                continue;
            }

            Long debtorUserId = loanContractRepository
                    .findDebtorUserIdByContractId(schedule.getContractId())
                    .orElse(null);
            if (debtorUserId == null) {
                log.warn("[repaymentDueReminder] 채무자 조회 실패 - contractId={}", schedule.getContractId());
                continue;
            }

            try {
                notificationService.createIfAbsentInNewTransaction(
                        debtorUserId,
                        stage.type(),
                        stage.title(),
                        stage.contentFor(schedule.getDueDate()),
                        schedule.getScheduleId(),
                        null
                );
                processed++;
            } catch (Exception e) {
                log.error("[repaymentDueReminder] 알림 생성 실패, 다음 스케줄 계속 진행 scheduleId={}", schedule.getScheduleId(), e);
            }
        }
        return processed;
    }

    private NotificationStage resolveStage(LocalDate dueDate, LocalDate today) {
        long daysUntilDue = ChronoUnit.DAYS.between(today, dueDate);
        return switch ((int) daysUntilDue) {
            case 3 -> NotificationStage.D3;
            case 1 -> NotificationStage.D1;
            case 0 -> NotificationStage.DDAY;
            default -> null;
        };
    }

    private enum NotificationStage {
        D3(NotificationType.REPAYMENT_DUE_REMINDER_D3, "상환 예정일 안내 (D-3)") {
            String contentFor(LocalDate dueDate) {
                return String.format("3일 후(%s) 상환 예정입니다.", dueDate);
            }
        },
        D1(NotificationType.REPAYMENT_DUE_REMINDER_D1, "상환 예정일 안내 (D-1)") {
            String contentFor(LocalDate dueDate) {
                return String.format("내일(%s) 상환 예정입니다.", dueDate);
            }
        },
        DDAY(NotificationType.REPAYMENT_DUE_REMINDER_DDAY, "상환 예정일 안내 (오늘)") {
            String contentFor(LocalDate dueDate) {
                return "오늘 상환 예정입니다.";
            }
        };

        private final NotificationType type;
        private final String title;

        NotificationStage(NotificationType type, String title) {
            this.type = type;
            this.title = title;
        }

        NotificationType type() { return type; }
        String title() { return title; }
        abstract String contentFor(LocalDate dueDate);
    }
}