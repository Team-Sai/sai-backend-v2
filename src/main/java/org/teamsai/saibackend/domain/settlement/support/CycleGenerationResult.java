package org.teamsai.saibackend.domain.settlement.support;

import org.teamsai.saibackend.domain.settlement.entity.Settlement;

public record CycleGenerationResult(CycleGenerationStatus result, Settlement settlement) {

    public static CycleGenerationResult created(Settlement settlement) {
        return new CycleGenerationResult(CycleGenerationStatus.CREATED, settlement);
    }

    public static CycleGenerationResult concurrentlySkipped() {
        return new CycleGenerationResult(CycleGenerationStatus.CONCURRENTLY_SKIPPED, null);
    }

    public static CycleGenerationResult noActiveParticipant() {
        return new CycleGenerationResult(CycleGenerationStatus.NO_ACTIVE_PARTICIPANT, null);
    }
}