package org.teamsai.saibackend.domain.calendar.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.dto.response.*;
import org.teamsai.saibackend.domain.calendar.entity.PreparationProposal;
import org.teamsai.saibackend.domain.calendar.entity.RepaymentPreparationEvent;
import org.teamsai.saibackend.domain.calendar.exception.PreparationEventErrorCode;
import org.teamsai.saibackend.domain.calendar.repository.PreparationProposalRepository;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.user.entity.User;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PreparationProposalConfirmationService {

    private final PreparationProposalRepository proposalRepository;
    private final RepaymentPreparationEventRepository eventRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final PreparationPlanningContextService contextService;
    private final PreparationPlanningValidator validator;
    private final EntityManager entityManager;
    private final Clock clock;

    private final JsonMapper mapper = JsonMapper.builder().build();

    public PreparationProposalConfirmationService(
            PreparationProposalRepository proposalRepository,
            RepaymentPreparationEventRepository eventRepository,
            RepaymentScheduleRepository scheduleRepository,
            PreparationPlanningContextService contextService,
            PreparationPlanningValidator validator,
            EntityManager entityManager,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.proposalRepository = proposalRepository;
        this.eventRepository = eventRepository;
        this.scheduleRepository = scheduleRepository;
        this.contextService = contextService;
        this.validator = validator;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PreparationConfirmationResponse confirm(
            Long userId,
            String proposalId
    ) {
        if (userId == null) {
            throw PreparationEventErrorCode.UNAUTHENTICATED.toException();
        }

        PreparationProposal proposal =
                proposalRepository.findOwnedForUpdate(
                        proposalId,
                        userId
                ).orElseThrow(
                        PreparationEventErrorCode.PROPOSAL_NOT_FOUND::toException
                );

        /*
         * 이미 등록된 제안은 만료 여부보다 먼저 처리한다.
         * 최초 승인 응답을 잃어버렸더라도 같은 ID로 재확인할 수 있다.
         */
        if (proposal.isConfirmed()) {
            PreparationConfirmationResponse saved =
                    mapper.readValue(
                            proposal.getResultJson(),
                            PreparationConfirmationResponse.class
                    );

            return new PreparationConfirmationResponse(
                    saved.proposalId(),
                    saved.confirmedAt(),
                    true,
                    saved.events()
            );
        }

        if (!clock.instant().isBefore(proposal.getExpiresAt())) {
            throw PreparationEventErrorCode.PROPOSAL_EXPIRED.toException();
        }

        /*
         * 기존 단건 준비 일정 등록과 같은 사용자 잠금을 사용한다.
         * 사용자 일정의 동시 등록을 직렬화한다.
         */
        User user = entityManager.find(
                User.class,
                userId,
                LockModeType.PESSIMISTIC_WRITE
        );

        if (user == null) {
            throw PreparationEventErrorCode.UNAUTHENTICATED.toException();
        }

        StoredPreparationProposal stored =
                mapper.readValue(
                        proposal.getPayloadJson(),
                        StoredPreparationProposal.class
                );

        if (stored.rescheduleTarget() != null) {
            return confirmReschedule(
                    userId,
                    proposal,
                    stored
            );
        }

        List<PreparationProposalItem> originals = stored.items();

        if (originals == null
                || originals.isEmpty()
                || originals.size() > 20) {
            throw PreparationEventErrorCode.PROPOSAL_CHANGED.toException();
        }

        List<Long> scheduleIds = originals.stream()
                .map(PreparationProposalItem::scheduleId)
                .distinct()
                .sorted()
                .toList();

        if (scheduleIds.size() != originals.size()) {
            throw PreparationEventErrorCode.PROPOSAL_CHANGED.toException();
        }

        /*
         * 일정 등록 전에 회차 잠금을 고정된 순서로 획득한다.
         */
        for (Long scheduleId : scheduleIds) {
            var schedule = scheduleRepository
                    .findByIdForUpdate(scheduleId)
                    .orElseThrow(
                            PreparationEventErrorCode.PROPOSAL_CHANGED::toException
                    );

            if (!schedule.getStatus().isUnresolved()) {
                throw PreparationEventErrorCode.PROPOSAL_CHANGED.toException();
            }
        }

        PreparationPlanningContext latest;

        try {
            latest = contextService.load(
                    userId,
                    stored.request().yearMonth()
            );
        } catch (IllegalArgumentException exception) {
            // 예: 제안 후 날짜가 바뀌어 대상 월이 달라진 경우
            throw PreparationEventErrorCode.PROPOSAL_CHANGED.toException();
        }

        Map<Long, RepaymentCandidate> latestCandidates =
                latest.candidates().stream()
                        .collect(Collectors.toMap(
                                RepaymentCandidate::scheduleId,
                                Function.identity()
                        ));

        List<RepaymentCandidate> selectedCandidates =
                new ArrayList<>();

        for (PreparationProposalItem original : originals) {
            RepaymentCandidate current =
                    latestCandidates.get(original.scheduleId());

            /*
             * 완료, 다른 준비 일정 등록, 계약 제외 등의 경우
             * 최신 신규 제안 후보에서 빠지므로 거절한다.
             */
            if (current == null
                    || !current.contractId()
                    .equals(original.contractId())
                    || !current.dueDate()
                    .equals(original.dueDate())
                    || current.remainingAmount()
                    .compareTo(original.remainingAmount()) != 0) {
                throw PreparationEventErrorCode.PROPOSAL_CHANGED.toException();
            }

            selectedCandidates.add(current);
        }

        /*
         * 제안 이후 새 채무가 생겨도 이 제안에 포함된 회차만 검증한다.
         */
        PreparationPlanningContext selectedContext =
                new PreparationPlanningContext(
                        latest.analysisDate(),
                        latest.targetMonth(),
                        List.copyOf(selectedCandidates),
                        latest.existingEvents(),
                        latest.alreadyPlannedScheduleIds()
                );

        PreparationAgentDraft draft =
                new PreparationAgentDraft(
                        originals.stream()
                                .map(item ->
                                        new PreparationAgentDraft.Slot(
                                                item.scheduleId(),
                                                item.startsAt().toString(),
                                                item.reason()
                                        )
                                )
                                .toList()
                );

        validator.validateRequest(stored.request());

        PreparationValidationResult validation =
                validator.validate(
                        selectedContext,
                        stored.request(),
                        draft,
                        clock.instant()
                );

        if (!validation.valid()) {
            throw PreparationEventErrorCode.PROPOSAL_CHANGED.toException();
        }

        // DB 잠금 대기 중 만료될 수 있으므로 저장 직전에 다시 확인한다.
        Instant confirmedAt = clock.instant();

        if (!confirmedAt.isBefore(proposal.getExpiresAt())) {
            throw PreparationEventErrorCode.PROPOSAL_EXPIRED.toException();
        }

        List<RepaymentPreparationEvent> newEvents =
                validation.items().stream()
                        .map(item -> {
                            String title = (
                                    item.contractName() == null
                                            || item.contractName().isBlank()
                                            ? "상환"
                                            : item.contractName()
                            ) + " 준비";

                            if (title.length() > 120) {
                                title = title.substring(0, 120);
                            }

                            return new RepaymentPreparationEvent(
                                    userId,
                                    item.contractId(),
                                    item.scheduleId(),
                                    title,
                                    item.startsAt(),
                                    item.endsAt(),
                                    confirmedAt
                            );
                        })
                        .toList();

        List<PreparationEventResponse> savedEvents =
                eventRepository.saveAllAndFlush(newEvents)
                        .stream()
                        .map(PreparationEventResponse::from)
                        .toList();

        PreparationConfirmationResponse result =
                new PreparationConfirmationResponse(
                        proposal.getProposalId(),
                        confirmedAt,
                        false,
                        savedEvents
                );

        proposal.confirm(
                confirmedAt,
                mapper.writeValueAsString(result)
        );

        /*
         * proposal는 현재 트랜잭션의 managed entity다.
         * 일정들과 승인 결과가 같은 트랜잭션에서 커밋된다.
         */
        return result;
    }

    private PreparationConfirmationResponse confirmReschedule(
            Long userId,
            PreparationProposal proposal,
            StoredPreparationProposal stored
    ) {
        PreparationRescheduleTarget target =
                stored.rescheduleTarget();

        List<PreparationProposalItem> originals =
                stored.items();

        if (originals == null
                || originals.size() != 1
                || target.revision() == null) {
            throw PreparationEventErrorCode
                    .PROPOSAL_CHANGED
                    .toException();
        }

        PreparationProposalItem original = originals.get(0);

        RepaymentPreparationEvent event =
                eventRepository.findOwnedEventForUpdate(
                        target.eventId(),
                        userId
                ).orElseThrow(
                        PreparationEventErrorCode
                                .PROPOSAL_CHANGED::toException
                );

        if (!Objects.equals(
                event.getRevision(),
                target.revision()
        )
                || !event.getScheduleId().equals(
                target.scheduleId()
        )
                || !event.getScheduleId().equals(
                original.scheduleId()
        )
                || !event.getContractId().equals(
                original.contractId()
        )
                || !event.getStartsAt().equals(
                target.startsAt()
        )
                || !event.getEndsAt().equals(
                target.endsAt()
        )) {
            throw PreparationEventErrorCode
                    .PROPOSAL_CHANGED
                    .toException();
        }

        var schedule = scheduleRepository.findByIdForUpdate(
                event.getScheduleId()
        ).orElseThrow(
                PreparationEventErrorCode
                        .PROPOSAL_CHANGED::toException
        );

        if (!schedule.getStatus().isUnresolved()) {
            throw PreparationEventErrorCode
                    .PROPOSAL_CHANGED
                    .toException();
        }

        Instant now = clock.instant();

        if (event.getReminderProcessedAt() != null
                || !event.getStartsAt().isAfter(now)) {
            throw PreparationEventErrorCode
                    .PROPOSAL_CHANGED
                    .toException();
        }

        PreparationPlanningContext latest;

        try {
            latest = contextService.load(
                    userId,
                    stored.request().yearMonth()
            );
        } catch (IllegalArgumentException exception) {
            throw PreparationEventErrorCode
                    .PROPOSAL_CHANGED
                    .toException();
        }

        RepaymentCandidate candidate =
                latest.allCandidates().stream()
                        .filter(item ->
                                item.scheduleId().equals(
                                        original.scheduleId()
                                )
                        )
                        .findFirst()
                        .orElseThrow(
                                PreparationEventErrorCode
                                        .PROPOSAL_CHANGED::toException
                        );

        if (!candidate.contractId().equals(
                original.contractId()
        )
                || !candidate.dueDate().equals(
                original.dueDate()
        )
                || candidate.remainingAmount().compareTo(
                original.remainingAmount()
        ) != 0) {
            throw PreparationEventErrorCode
                    .PROPOSAL_CHANGED
                    .toException();
        }

        PreparationPlanningContext selected =
                new PreparationPlanningContext(
                        latest.analysisDate(),
                        latest.targetMonth(),
                        List.of(candidate),
                        latest.existingEvents(),
                        latest.alreadyPlannedScheduleIds().stream()
                                .filter(id ->
                                        !id.equals(candidate.scheduleId())
                                )
                                .toList()
                );

        PreparationAgentDraft draft =
                new PreparationAgentDraft(
                        List.of(
                                new PreparationAgentDraft.Slot(
                                        original.scheduleId(),
                                        original.startsAt().toString(),
                                        original.reason()
                                )
                        )
                );

        validator.validateRequest(stored.request());

        PreparationValidationResult validation =
                validator.validate(
                        selected,
                        stored.request(),
                        draft,
                        clock.instant()
                );

        if (!validation.valid()) {
            throw PreparationEventErrorCode
                    .PROPOSAL_CHANGED
                    .toException();
        }

        PreparationProposalItem item =
                validation.items().get(0);

        Instant confirmedAt = clock.instant();

        if (!confirmedAt.isBefore(proposal.getExpiresAt())) {
            throw PreparationEventErrorCode
                    .PROPOSAL_EXPIRED
                    .toException();
        }

        if (!event.getStartsAt().isAfter(confirmedAt)
                || !item.startsAt().isAfter(confirmedAt)
                || item.startsAt().equals(event.getStartsAt())) {
            throw PreparationEventErrorCode
                    .PROPOSAL_CHANGED
                    .toException();
        }

        event.reschedule(
                item.startsAt(),
                item.endsAt(),
                confirmedAt
        );

        eventRepository.flush();

        PreparationConfirmationResponse result =
                new PreparationConfirmationResponse(
                        proposal.getProposalId(),
                        confirmedAt,
                        false,
                        List.of(
                                PreparationEventResponse.from(event)
                        )
                );

        proposal.confirm(
                confirmedAt,
                mapper.writeValueAsString(result)
        );

        return result;
    }
}