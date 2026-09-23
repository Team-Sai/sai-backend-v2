package org.teamsai.saibackend.domain.calendar.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;


@Controller
public class CalendarController {

    @Operation(hidden = true)
    @GetMapping("/calendar")
    public String calendarPage(){
        return "calendar/calendar";
    }
}