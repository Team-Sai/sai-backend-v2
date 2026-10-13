package org.teamsai.saibackend.domain.archive.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Slf4j
@Controller
public class ArchiveController {

    @GetMapping("/archive")
    public String archivePage() {
        return "archive/archive";
    }
}
