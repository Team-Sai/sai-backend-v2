package org.teamsai.saibackend.domain.contract.controller;


import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;


@Controller
public class ChangeRequestDetailController {


    @Hidden
    @GetMapping("/contracts/{contractId}/change-requests/{changeRequestId}")
    public String changeRequestDetailPage(
            @PathVariable Long contractId,
            @PathVariable Long changeRequestId,
            Model model
    ) {
        model.addAttribute("contractId", contractId);
        model.addAttribute("changeRequestId", changeRequestId);
        return "contract/request-detail";
    }
}
