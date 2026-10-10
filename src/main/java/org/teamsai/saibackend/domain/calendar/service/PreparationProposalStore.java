package org.teamsai.saibackend.domain.calendar.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationProposalItemResponse;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationRescheduleTargetResponse;
import org.teamsai.saibackend.domain.calendar.dto.internal.StoredPreparationProposal;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.*;
import org.teamsai.saibackend.domain.calendar.entity.PreparationProposal;
import org.teamsai.saibackend.domain.calendar.repository.PreparationProposalRepository;
import org.teamsai.saibackend.domain.calendar.type.PreparationProposalStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PreparationProposalStore {

    private final PreparationProposalRepository repository;
    private final Clock clock;

    private final JsonMapper mapper = JsonMapper.builder().build();

    public PreparationProposalStore(
            PreparationProposalRepository repository,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public PreparationProposalResponse save(
            Long userId,
            PreparationProposalRequest request,
            List<PreparationProposalItemResponse> items,
            int attempts
    ) {
        return save(
                userId,
                request,
                items,
                attempts,
                null
        );
    }

    @Transactional
    public PreparationProposalResponse save(
            Long userId,
            PreparationProposalRequest request,
            List<PreparationProposalItemResponse> items,
            int attempts,
            PreparationRescheduleTargetResponse target
    ) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofMinutes(15));
        String proposalId = UUID.randomUUID().toString();

        StoredPreparationProposal payload =
                new StoredPreparationProposal(
                        request,
                        List.copyOf(items),
                        target
                );

        repository.saveAndFlush(
                new PreparationProposal(
                        proposalId,
                        userId,
                        mapper.writeValueAsString(payload),
                        now,
                        expiresAt
                )
        );

        return PreparationProposalResponse.builder()
                .status(PreparationProposalStatus.READY)
                .proposedAt(now)
                .attempts(attempts)
                .message(
                        target == null
                                ? "검증된 제안입니다. 확인 후 15분 이내에 등록하세요."
                                : "검증된 변경 제안입니다. 승인 전에는 기존 일정이 유지됩니다."
                )
                .items(items)
                .violations(List.of())
                .proposalId(proposalId)
                .expiresAt(expiresAt)
                .rescheduleTarget(target)
                .build();
    }
}