package org.teamsai.saibackend.domain.calendar.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.repository.PreparationProposalRepository;

import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PreparationProposalCleanupService {

    private final PreparationProposalRepository repository;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public int deleteChunk(
            Instant cutoff,
            int chunkSize
    ) {
        Objects.requireNonNull(cutoff, "cutoff");

        if (chunkSize < 1 || chunkSize > 1000) {
            throw new IllegalArgumentException(
                    "정리 청크 크기는 1~1000이어야 합니다."
            );
        }

        return repository.deleteExpiredUnconfirmedChunk(
                cutoff,
                chunkSize
        );
    }
}