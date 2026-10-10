package org.teamsai.saibackend.domain.calendar.service;

import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationCoordinationFacts;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationPlanningContext;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationProposalItem;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PreparationCoordinationFactsService {

    public PreparationCoordinationFacts calculate(
            PreparationPlanningContext context,
            PreparationProposalRequest request,
            List<PreparationProposalItem> validatedItems,
            boolean reschedule
    ) {
        Map<Instant, Long> countsByTime = validatedItems.stream()
                .map(PreparationProposalItem::startsAt)
                .collect(Collectors.groupingBy(
                        Function.identity(),
                        Collectors.counting()
                ));

        int sharedTimeGroupCount = Math.toIntExact(
                countsByTime.values().stream()
                        .filter(count -> count >= 2)
                        .count()
        );

        int groupedScheduleCount = Math.toIntExact(
                countsByTime.values().stream()
                        .filter(count -> count >= 2)
                        .mapToLong(Long::longValue)
                        .sum()
        );

        return new PreparationCoordinationFacts(
                context.allCandidates().size(),
                context.existingEvents().size(),
                context.candidates().size(),
                validatedItems.size(),
                sharedTimeGroupCount,
                groupedScheduleCount,
                request.allowedDays().stream().sorted().toList(),
                request.windowStart(),
                request.windowEnd(),
                request.leadDays(),
                request.preferences() == null
                        ? ""
                        : request.preferences().strip(),
                reschedule
        );
    }
}