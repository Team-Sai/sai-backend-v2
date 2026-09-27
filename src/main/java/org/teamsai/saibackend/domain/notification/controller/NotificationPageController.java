package org.teamsai.saibackend.domain.notification.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class NotificationPageController {

    @Hidden
    @GetMapping("/notifications")
    public String notificationCenterPage() {
        return "notification/notification-center";
    }
}