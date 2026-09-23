package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController  {

    @Hidden
    @GetMapping("/contract")
    public String dashboardPage(){

        return "contract/dashboard";
    }
}
