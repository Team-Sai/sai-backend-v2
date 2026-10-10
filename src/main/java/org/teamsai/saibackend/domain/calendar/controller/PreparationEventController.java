package org.teamsai.saibackend.domain.calendar.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationEventCreateRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationEventResponse;
import org.teamsai.saibackend.domain.calendar.service.RepaymentPreparationEventService;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/calendar/preparation-events")
public class PreparationEventController {

    private final RepaymentPreparationEventService service;

    @GetMapping
    public List<PreparationEventResponse> findMonth(
            @AuthenticationPrincipal(expression = "userId") Long userId,
            @RequestParam
            @DateTimeFormat(pattern = "yyyy-MM")
            YearMonth yearMonth
    ) {
        return service.findMonth(userId, yearMonth);
    }

    @PostMapping
    public PreparationEventResponse create(
            @AuthenticationPrincipal(expression = "userId") Long userId,
            @RequestBody PreparationEventCreateRequest request
    ) {
        return service.create(userId, request);
    }
}