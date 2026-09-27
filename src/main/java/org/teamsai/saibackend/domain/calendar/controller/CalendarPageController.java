package org.teamsai.saibackend.domain.calendar.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CalendarPageController {

    @Hidden
    @GetMapping("/calendar")
    public String calendarPage() {
        return "calendar/calendar";
    }
}