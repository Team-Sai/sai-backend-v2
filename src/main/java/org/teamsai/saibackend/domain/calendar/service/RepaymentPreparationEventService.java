package org.teamsai.saibackend.domain.calendar.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationEventCreateRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationEventResponse;
import org.teamsai.saibackend.domain.calendar.entity.RepaymentPreparationEvent;
import org.teamsai.saibackend.domain.calendar.exception.PreparationEventErrorCode;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;
import org.teamsai.saibackend.domain.user.entity.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class RepaymentPreparationEventService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final RepaymentPreparationEventRepository eventRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final RepaymentAnalysisService analysisService;
    private final EntityManager entityManager;
    private final Clock clock;

    public RepaymentPreparationEventService(
            RepaymentPreparationEventRepository eventRepository,
            RepaymentScheduleRepository scheduleRepository,
            RepaymentAnalysisService analysisService,
            EntityManager entityManager,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.eventRepository = eventRepository;
        this.scheduleRepository = scheduleRepository;
        this.analysisService = analysisService;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public List<PreparationEventResponse> findMonth(
            Long userId,
            YearMonth month
    ) {
        requireUser(userId);

        if (month == null) {
            throw PreparationEventErrorCode.INVALID_REQUEST.toException();
        }

        Instant rangeStart = month.atDay(1)
                .atStartOfDay(ZONE)
                .toInstant();

        Instant rangeEnd = month.plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZONE)
                .toInstant();

        return eventRepository
                .findOverlapping(userId, rangeStart, rangeEnd)
                .stream()
                .map(PreparationEventResponse::from)
                .toList();
    }

    @Transactional
    public PreparationEventResponse create(
            Long userId,
            PreparationEventCreateRequest request
    ) {
        requireUser(userId);
        validateRequest(request);

        /*
         * 같은 사용자의 동시 등록을 직렬화한다.
         * 조회 후 저장 사이에 다른 요청이 충돌 일정을 넣는 것을 방지한다.
         */
        User user = entityManager.find(
                User.class,
                userId,
                LockModeType.PESSIMISTIC_WRITE
        );

        if (user == null) {
            throw PreparationEventErrorCode.UNAUTHENTICATED.toException();
        }

        var existing = eventRepository.findByUserIdAndScheduleId(
                userId,
                request.scheduleId()
        );

        if (existing.isPresent()) {
            RepaymentPreparationEvent event = existing.get();

            boolean sameRequest =
                    event.getContractId().equals(request.contractId())
                            && event.getStartsAt().equals(request.startsAt())
                            && event.getEndsAt().equals(request.endsAt());

            if (sameRequest) {
                return PreparationEventResponse.from(event);
            }

            throw PreparationEventErrorCode.ALREADY_REGISTERED.toException();
        }

        validateTime(request);

        /*
         * 기존 상환 처리도 사용하는 회차 잠금을 획득한다.
         * 잠금 획득 후 미상환 상태를 다시 확인한다.
         */
        var schedule = scheduleRepository
                .findByIdForUpdate(request.scheduleId())
                .orElseThrow(
                        PreparationEventErrorCode.CANDIDATE_NOT_FOUND::toException
                );

        if (!schedule.getContractId().equals(request.contractId())
                || !schedule.getStatus().isUnresolved()) {
            throw PreparationEventErrorCode.CANDIDATE_NOT_FOUND.toException();
        }

        RepaymentCandidate candidate = analysisService
                .analyze(userId)
                .candidates()
                .stream()
                .filter(c ->
                        c.contractId().equals(request.contractId())
                                && c.scheduleId().equals(request.scheduleId())
                )
                .findFirst()
                .orElseThrow(
                        PreparationEventErrorCode.CANDIDATE_NOT_FOUND::toException
                );

        validateDeadline(candidate, request);

        String contractName = candidate.contractName();
        String title = (
                contractName == null || contractName.isBlank()
                        ? "상환"
                        : contractName
        ) + " 준비";

        if (title.length() > 120) {
            title = title.substring(0, 120);
        }

        RepaymentPreparationEvent event =
                new RepaymentPreparationEvent(
                        userId,
                        candidate.contractId(),
                        candidate.scheduleId(),
                        title,
                        request.startsAt(),
                        request.endsAt(),
                        clock.instant()
                );

        return PreparationEventResponse.from(
                eventRepository.saveAndFlush(event)
        );
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw PreparationEventErrorCode.UNAUTHENTICATED.toException();
        }
    }

    private void validateRequest(
            PreparationEventCreateRequest request
    ) {
        if (request == null
                || request.contractId() == null
                || request.scheduleId() == null
                || request.contractId() <= 0
                || request.scheduleId() <= 0
                || request.startsAt() == null
                || request.endsAt() == null) {
            throw PreparationEventErrorCode.INVALID_REQUEST.toException();
        }

        // DB 저장 정밀도와 재요청 비교를 일치시킨다.
        if (request.startsAt().getNano() != 0
                || request.endsAt().getNano() != 0) {
            throw PreparationEventErrorCode.INVALID_REQUEST.toException();
        }
    }

    private void validateTime(
            PreparationEventCreateRequest request
    ) {
        Instant start = request.startsAt();
        Instant end = request.endsAt();

        LocalDate startDate = start.atZone(ZONE).toLocalDate();
        LocalDate endDate = end.atZone(ZONE).toLocalDate();

        if (!start.isAfter(clock.instant())
                || !end.isAfter(start)
                || Duration.between(start, end)
                .compareTo(Duration.ofMinutes(60)) > 0
                || !startDate.equals(endDate)) {
            throw PreparationEventErrorCode.INVALID_TIME.toException();
        }
    }

    private void validateDeadline(
            RepaymentCandidate candidate,
            PreparationEventCreateRequest request
    ) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));

        // 이미 지난 납기: 오늘 이후의 확인 일정으로 등록할 수 있다.
        if (candidate.dueDate().isBefore(today)) {
            return;
        }

        Instant dueDateEndExclusive = candidate.dueDate()
                .plusDays(1)
                .atStartOfDay(ZONE)
                .toInstant();

        if (request.endsAt().isAfter(dueDateEndExclusive)) {
            throw PreparationEventErrorCode.AFTER_DUE_DATE.toException();
        }
    }
}