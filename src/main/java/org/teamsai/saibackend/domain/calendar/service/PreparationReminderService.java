package org.teamsai.saibackend.domain.calendar.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.entity.RepaymentPreparationEvent;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PreparationReminderService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_LABEL =
            DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final RepaymentPreparationEventRepository events;
    private final RepaymentScheduleRepository schedules;
    private final RepaymentAnalysisService analysis;
    private final NotificationService notifications;
    private final EntityManager entityManager;
    private final Clock clock;
    private final Duration maxReminderDelay;

    public PreparationReminderService(
            RepaymentPreparationEventRepository events,
            RepaymentScheduleRepository schedules,
            RepaymentAnalysisService analysis,
            NotificationService notifications,
            EntityManager entityManager,
            @Qualifier("repaymentClock") Clock clock,
            @Value("${sai.calendar.preparation-reminder-max-delay:PT24H}")
            Duration maxReminderDelay
    ) {
        if (maxReminderDelay == null
                || maxReminderDelay.isZero()
                || maxReminderDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "Preparation reminder max delay must be positive"
            );
        }

        this.events = events;
        this.schedules = schedules;
        this.analysis = analysis;
        this.notifications = notifications;
        this.entityManager = entityManager;
        this.clock = clock;
        this.maxReminderDelay = maxReminderDelay;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int processUser(Long userId, Instant cutoff) {
        // 일정 등록·승인과 동일하게 사용자 행부터 잠급니다.
        // 다른 서버의 배치도 이 잠금을 획득한 뒤 미처리 일정을 재조회합니다.
        User user = entityManager.find(
                User.class, userId, LockModeType.PESSIMISTIC_WRITE
        );
        if (user == null) {
            throw new IllegalStateException("Reminder user does not exist");
        }

        List<RepaymentPreparationEvent> due =
                events.findDueRemindersByUserId(userId, cutoff);

        if (due.isEmpty()) {
            return 0;
        }

        Instant evaluatedAt = clock.instant();
        Instant oldestAllowedStart = evaluatedAt.minus(maxReminderDelay);

        List<RepaymentPreparationEvent> expired = due.stream()
                .filter(event ->
                        event.getStartsAt().isBefore(oldestAllowedStart)
                )
                .toList();

        List<RepaymentPreparationEvent> deliverable = due.stream()
                .filter(event ->
                        !event.getStartsAt().isBefore(oldestAllowedStart)
                )
                .toList();

        expired.forEach(event ->
                event.markReminderProcessed(evaluatedAt)
        );

        if (!expired.isEmpty()) {
            log.info(
                    "event=PREPARATION_REMINDER_EXPIRED "
                            + "userId={} eventCount={} maxDelaySeconds={}",
                    userId,
                    expired.size(),
                    maxReminderDelay.toSeconds()
            );
        }

        if (deliverable.isEmpty()) {
            events.flush();
            return 0;
        }

        Set<Long> missingScheduleIds = new HashSet<>();

        List<Long> scheduleIds = deliverable.stream()
                .map(RepaymentPreparationEvent::getScheduleId)
                .distinct()
                .sorted()
                .toList();

        for (Long scheduleId : scheduleIds) {
            if (schedules.findByIdForUpdate(scheduleId).isEmpty()) {
                missingScheduleIds.add(scheduleId);

                log.warn(
                        "event=PREPARATION_REMINDER_SCHEDULE_MISSING "
                                + "userId={} scheduleId={}",
                        userId,
                        scheduleId
                );
            }
        }

        // 잠금 획득 이후 확정 상환 기록 기준으로 금액을 다시 읽습니다.
        // READ_COMMITTED로 잠금 대기 전에 만들어진 오래된 스냅샷을 피합니다.
        Map<Long, RepaymentCandidate> current = analysis.analyze(userId)
                .candidates().stream()
                .collect(Collectors.toMap(
                        RepaymentCandidate::scheduleId, candidate -> candidate
                ));

        Map<Instant, List<RepaymentPreparationEvent>> groups =
                deliverable.stream().collect(Collectors.groupingBy(
                        RepaymentPreparationEvent::getStartsAt,
                        TreeMap::new,
                        Collectors.toList()
                ));

        int notificationGroups = 0;
        for (var entry : groups.entrySet()) {
            List<RepaymentPreparationEvent> group = entry.getValue();
            List<RepaymentPreparationEvent> active = group.stream()
                    .filter(event ->
                            !missingScheduleIds.contains(event.getScheduleId())
                    )
                    .filter(event -> {
                        RepaymentCandidate candidate =
                                current.get(event.getScheduleId());
                        return candidate != null
                                && candidate.contractId().equals(event.getContractId())
                                && candidate.remainingAmount().signum() > 0;
                    }).toList();

            if (!active.isEmpty()) {
                BigDecimal remaining = active.stream()
                        .map(event -> current.get(event.getScheduleId()).remainingAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                long referenceId = group.stream()
                        .mapToLong(RepaymentPreparationEvent::getEventId)
                        .min().orElseThrow();
                var localTime = entry.getKey().atZone(SEOUL);
                long dateKey = Long.parseLong(
                        localTime.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
                );

                notifications.createIfAbsent(
                        userId,
                        NotificationType.REPAYMENT_PREPARATION_REMINDER,
                        "상환 확인 시간입니다",
                        localTime.format(TIME_LABEL) + "에 등록한 확인 일정입니다. "
                                + "미상환 회차 " + active.size() + "건, 현재 잔여액 "
                                + remaining.stripTrailingZeros().toPlainString()
                                + "원입니다. 상환 기록과 자금 준비 상태를 확인하세요.",
                        referenceId,
                        dateKey
                );
                notificationGroups++;
            }

            // 알림 저장이 실패하면 여기까지 진행하지 않습니다.
            // 이후 다른 그룹에서 실패해도 사용자 전체 트랜잭션이 롤백됩니다.
            Instant processedAt = clock.instant();
            group.forEach(event -> event.markReminderProcessed(processedAt));
        }

        events.flush();

        log.info(
                "event=PREPARATION_REMINDER_PROCESSED "
                        + "eventCount={} expiredEventCount={} notificationGroups={}",
                due.size(),
                expired.size(),
                notificationGroups
        );

        return notificationGroups;
    }
}