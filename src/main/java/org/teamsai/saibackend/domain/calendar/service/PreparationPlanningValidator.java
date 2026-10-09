package org.teamsai.saibackend.domain.calendar.service;

import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.*;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PreparationPlanningValidator {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public void validateRequest(PreparationProposalRequest request) {
        if (request == null
                || request.yearMonth() == null
                || request.allowedDays() == null
                || request.allowedDays().isEmpty()
                || request.allowedDays().stream().anyMatch(Objects::isNull)
                || request.windowStart() == null
                || request.windowEnd() == null
                || request.durationMinutes() == null
                || request.leadDays() == null) {
            throw new IllegalArgumentException(
                    "일정 제안 조건을 모두 입력하세요."
            );
        }

        if (!request.windowEnd().isAfter(request.windowStart())
                || request.windowStart().getSecond() != 0
                || request.windowEnd().getSecond() != 0
                || request.windowStart().getNano() != 0
                || request.windowEnd().getNano() != 0
                || request.durationMinutes() < 1
                || request.durationMinutes() > 60
                || request.leadDays() < 0
                || request.leadDays() > 14) {
            throw new IllegalArgumentException(
                    "가능 시간, 소요 시간 또는 준비 기간이 올바르지 않습니다."
            );
        }

        long availableMinutes = Duration.between(
                request.windowStart(),
                request.windowEnd()
        ).toMinutes();

        if (availableMinutes < request.durationMinutes()) {
            throw new IllegalArgumentException(
                    "가능 시간대가 일정 소요 시간보다 짧습니다."
            );
        }

        if (request.preferences() != null
                && request.preferences().length() > 1000) {
            throw new IllegalArgumentException(
                    "추가 요청은 1000자 이내로 입력하세요."
            );
        }
        validateFunding(request);
    }

    public PreparationValidationResult validate(
            PreparationPlanningContext context,
            PreparationProposalRequest request,
            PreparationAgentDraft draft,
            Instant now
    ) {
        List<PreparationPlanningViolation> violations =
                new ArrayList<>();

        List<PreparationProposalItem> items =
                new ArrayList<>();

        if (draft == null || draft.slots() == null) {
            violations.add(new PreparationPlanningViolation(
                    null,
                    "INVALID_DRAFT",
                    "일정 후보 목록이 없습니다."
            ));

            return new PreparationValidationResult(
                    List.of(),
                    List.copyOf(violations)
            );
        }

        if (draft.slots().size() > 20) {
            violations.add(new PreparationPlanningViolation(
                    null,
                    "TOO_MANY_SLOTS",
                    "한 번에 제안할 수 있는 일정은 최대 20개입니다."
            ));

            return new PreparationValidationResult(
                    List.of(),
                    List.copyOf(violations)
            );
        }

        Map<Long, RepaymentCandidate> candidateMap =
                context.candidates().stream()
                        .collect(Collectors.toMap(
                                RepaymentCandidate::scheduleId,
                                Function.identity()
                        ));

        Set<Long> seen = new HashSet<>();

        LocalDate today = now.atZone(ZONE).toLocalDate();

        for (PreparationAgentDraft.Slot slot : draft.slots()) {
            if (slot == null || slot.scheduleId() == null) {
                add(
                        violations,
                        null,
                        "INVALID_SLOT",
                        "회차 ID가 없는 일정입니다."
                );
                continue;
            }

            Long scheduleId = slot.scheduleId();
            RepaymentCandidate candidate =
                    candidateMap.get(scheduleId);

            if (candidate == null) {
                add(
                        violations,
                        scheduleId,
                        "UNKNOWN_SCHEDULE",
                        "제안 대상에 없는 회차입니다."
                );
                continue;
            }

            if (!seen.add(scheduleId)) {
                add(
                        violations,
                        scheduleId,
                        "DUPLICATE_SCHEDULE",
                        "같은 회차를 두 번 제안했습니다."
                );
                continue;
            }

            Instant start;

            try {
                start = Instant.parse(slot.startsAt());
            } catch (RuntimeException exception) {
                add(
                        violations,
                        scheduleId,
                        "INVALID_START",
                        "시작 시각은 UTC ISO 형식이어야 합니다."
                );
                continue;
            }

            Instant end = start.plus(
                    Duration.ofMinutes(request.durationMinutes())
            );

            ZonedDateTime localStart = start.atZone(ZONE);
            ZonedDateTime localEnd = end.atZone(ZONE);

            int previousViolationCount = violations.size();

            if (!start.isAfter(now)) {
                add(
                        violations,
                        scheduleId,
                        "PAST_TIME",
                        "현재 시각 이후로 배치하세요."
                );
            }

            if (start.getNano() != 0
                    || localStart.getSecond() != 0) {
                add(
                        violations,
                        scheduleId,
                        "INVALID_PRECISION",
                        "시작 시각은 초가 0인 분 단위로 배치하세요."
                );
            }

            if (!localStart.toLocalDate()
                    .equals(localEnd.toLocalDate())) {
                add(
                        violations,
                        scheduleId,
                        "CROSSES_DAY",
                        "일정은 같은 날에 시작하고 종료해야 합니다."
                );
            }

            if (!YearMonth.from(localStart)
                    .equals(request.yearMonth())) {
                add(
                        violations,
                        scheduleId,
                        "OUTSIDE_MONTH",
                        "대상 월 안에 배치하세요."
                );
            }

            if (!request.allowedDays()
                    .contains(localStart.getDayOfWeek())) {
                add(
                        violations,
                        scheduleId,
                        "DISALLOWED_DAY",
                        "사용자가 허용한 요일에 배치하세요."
                );
            }

            if (localStart.toLocalTime()
                    .isBefore(request.windowStart())
                    || localEnd.toLocalTime()
                    .isAfter(request.windowEnd())) {
                add(
                        violations,
                        scheduleId,
                        "OUTSIDE_TIME_WINDOW",
                        "전체 일정이 사용자의 가능 시간대 안에 있어야 합니다."
                );
            }

            /*
             * 연체 회차는 미래의 확인 일정으로 배치한다.
             * 정상 납기 회차에만 leadDays를 적용한다.
             */
            if (!candidate.dueDate().isBefore(today)) {
                LocalDate preparationDeadline =
                        candidate.dueDate()
                                .minusDays(request.leadDays());

                if (localEnd.toLocalDate()
                        .isAfter(preparationDeadline)) {
                    add(
                            violations,
                            scheduleId,
                            "AFTER_PREPARATION_DEADLINE",
                            "납기에서 준비 기간을 뺀 날짜까지 배치하세요."
                    );
                }
            }

            if (slot.reason() == null
                    || slot.reason().isBlank()
                    || slot.reason().length() > 300) {
                add(
                        violations,
                        scheduleId,
                        "INVALID_REASON",
                        "제안 이유는 1자 이상 300자 이내여야 합니다."
                );
            }

            if (violations.size() == previousViolationCount) {
                items.add(new PreparationProposalItem(
                        candidate.contractId(),
                        candidate.scheduleId(),
                        candidate.contractName(),
                        candidate.remainingAmount(),
                        candidate.dueDate(),
                        candidate.dueDate().isBefore(today),
                        start,
                        end,
                        slot.reason().trim()
                ));
            }
        }

        for (RepaymentCandidate candidate : context.candidates()) {
            if (!seen.contains(candidate.scheduleId())) {
                add(
                        violations,
                        candidate.scheduleId(),
                        "MISSING_SCHEDULE",
                        "해당 미상환 회차의 준비 일정이 누락되었습니다."
                );
            }
        }

        /*
         * 일부만 통과해도 전체 제안이 실패하면
         * 등록 가능한 항목으로 노출하지 않는다.
         */
        if (!violations.isEmpty()) {
            return new PreparationValidationResult(
                    List.of(),
                    List.copyOf(violations)
            );
        }

        items.sort(
                Comparator.comparing(
                        PreparationProposalItem::startsAt
                ).thenComparing(
                        PreparationProposalItem::scheduleId
                )
        );

        return new PreparationValidationResult(
                List.copyOf(items),
                List.of()
        );
    }

    private void add(
            List<PreparationPlanningViolation> violations,
            Long scheduleId,
            String code,
            String message
    ) {
        violations.add(
                new PreparationPlanningViolation(
                        scheduleId,
                        code,
                        message
                )
        );
    }

    private void validateFunding(PreparationProposalRequest request) {
        var funding = request.funding();

        if (funding == null) {
            return;
        }

        if (funding.remainingMonthlyBudget() == null
                || funding.availableNow() == null
                || funding.expectedIncome() == null
                || funding.remainingMonthlyBudget().signum() < 0
                || funding.availableNow().signum() < 0
                || funding.availableNow()
                .compareTo(funding.remainingMonthlyBudget()) > 0
                || funding.expectedIncome().size() > 10) {
            throw new IllegalArgumentException(
                    "상환 예산과 사용 가능 금액을 확인하세요."
            );
        }

        for (var income : funding.expectedIncome()) {
            if (income == null
                    || income.availableDate() == null
                    || income.amount() == null
                    || income.amount().signum() <= 0
                    || !YearMonth.from(income.availableDate())
                    .equals(request.yearMonth())) {
                throw new IllegalArgumentException(
                        "예정 자금은 대상 월의 날짜와 양수 금액으로 입력하세요."
                );
            }
        }
    }
}