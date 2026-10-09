package org.teamsai.saibackend.domain.calendar.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationConfirmationResponse;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationPlanningContext;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationProposalResponse;
import org.teamsai.saibackend.domain.calendar.service.PreparationPlanningContextService;
import org.teamsai.saibackend.domain.calendar.service.PreparationProposalConfirmationService;
import org.teamsai.saibackend.domain.calendar.service.PreparationProposalService;

import java.time.YearMonth;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/calendar/preparation-planning")
public class PreparationPlanningController {

    private final PreparationPlanningContextService contextService;
    private final PreparationProposalService proposalService;
    private final PreparationProposalConfirmationService confirmationService;

    @GetMapping("/context")
    public PreparationPlanningContext getContext(
            @AuthenticationPrincipal(expression = "userId") Long userId,

            @RequestParam
            @DateTimeFormat(pattern = "yyyy-MM")
            YearMonth yearMonth
    ) {
        return contextService.load(userId, yearMonth);
    }

    @PostMapping("/proposals")
    public PreparationProposalResponse propose(
            @AuthenticationPrincipal(expression = "userId") Long userId,

            @RequestBody PreparationProposalRequest request
    ) {
        return proposalService.propose(userId, request);
    }

    @PostMapping("/events/{eventId}/reschedule-proposals")
    public PreparationProposalResponse proposeReschedule(
            @AuthenticationPrincipal(expression = "userId") Long userId,
            @PathVariable Long eventId,
            @RequestBody PreparationProposalRequest request
    ) {
        return proposalService.proposeReschedule(
                userId,
                eventId,
                request
        );
    }

    @PostMapping("/proposals/{proposalId}/confirm")
    public PreparationConfirmationResponse confirm(
            @AuthenticationPrincipal(expression = "userId") Long userId,
            @PathVariable String proposalId
    ) {
        return confirmationService.confirm(userId, proposalId);
    }
}