package org.teamsai.saibackend.domain.calendar.calculator;

import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationCoordinationFactsResponse;
import org.teamsai.saibackend.domain.calendar.dto.internal.PreparationPlanningContext;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationProposalItemResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PreparationCoordinationFactsCalculator {

    public PreparationCoordinationFactsResponse calculate(
            PreparationPlanningContext context,
            PreparationProposalRequest request,
            List<PreparationProposalItemResponse> validatedItems,
            boolean reschedule
    ) {
        Map<Instant, Long> countsByTime = validatedItems.stream()
                .map(PreparationProposalItemResponse::startsAt)
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

        return new PreparationCoordinationFactsResponse(
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