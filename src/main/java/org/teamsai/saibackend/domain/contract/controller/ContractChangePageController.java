package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ContractChangePageController {

    @Hidden
    @GetMapping("/contracts/{contractId}/change-request")
    public String changeRequestPage(
            @PathVariable Long contractId,
            Model model
    ) {
        model.addAttribute("contractId", contractId);
        return "contract/request";
    }

    @Hidden
    @GetMapping("/contracts/{contractId}/change-requests/{changeRequestId}/signature")
    public String changeRequestSignaturePage(
            @PathVariable Long contractId,
            @PathVariable Long changeRequestId,
            Model model
    ) {
        model.addAttribute("contractId", contractId);
        model.addAttribute("changeRequestId", changeRequestId);
        return "contract/change-request-signature";
    }
}
