package org.teamsai.saibackend.domain.calendar.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationPlanningContext;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationRescheduleTarget;
import org.teamsai.saibackend.domain.calendar.exception.PreparationEventErrorCode;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;

import java.time.Clock;
import java.util.List;

@Service
public class PreparationRescheduleContextService {

    private final PreparationPlanningContextService contexts;
    private final RepaymentPreparationEventRepository events;
    private final Clock clock;

    public PreparationRescheduleContextService(
            PreparationPlanningContextService contexts,
            RepaymentPreparationEventRepository events,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.contexts = contexts;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Plan load(
            Long userId,
            Long eventId,
            PreparationProposalRequest request
    ) {
        var event = events.findByEventIdAndUserId(
                eventId,
                userId
        ).orElseThrow(
                PreparationEventErrorCode.EVENT_NOT_FOUND::toException
        );

        if (event.getReminderProcessedAt() != null
                || !event.getStartsAt().isAfter(clock.instant())) {
            throw PreparationEventErrorCode
                    .EVENT_NOT_RESCHEDULABLE
                    .toException();
        }

        var base = contexts.loadForProposal(userId, request);

        var candidate = base.allCandidates().stream()
                .filter(item ->
                        item.scheduleId().equals(event.getScheduleId())
                                && item.contractId().equals(
                                event.getContractId()
                        )
                )
                .findFirst()
                .orElseThrow(
                        PreparationEventErrorCode
                                .CANDIDATE_NOT_FOUND::toException
                );

        var selected = new PreparationPlanningContext(
                base.analysisDate(),
                base.targetMonth(),
                List.of(candidate),
                base.existingEvents(),
                base.alreadyPlannedScheduleIds().stream()
                        .filter(id ->
                                !id.equals(candidate.scheduleId())
                        )
                        .toList(),
                base.allCandidates(),
                base.fundingAssessment(),
                base.repaymentHistory()
        );

        var target = new PreparationRescheduleTarget(
                event.getEventId(),
                event.getScheduleId(),
                event.getRevision(),
                event.getStartsAt(),
                event.getEndsAt()
        );

        return new Plan(selected, target);
    }

    public record Plan(
            PreparationPlanningContext context,
            PreparationRescheduleTarget target
    ) {
    }
}