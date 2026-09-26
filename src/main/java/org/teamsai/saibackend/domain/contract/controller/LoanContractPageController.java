package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class LoanContractPageController {

    @Hidden
    @GetMapping("/contracts/new")
    public String contractFormPage() {
        return "contract/contract-form";
    }

    @Hidden
    @GetMapping("/contracts/signature")
    public String contractSignaturePage() {
        return "contract/contract-signature";
    }

    @Hidden
    @GetMapping("/contracts/{contractId}/approve")
    public String contractDebtorApprovePage(
            @PathVariable Long contractId,
            Model model
    ) {
        model.addAttribute("contractId", contractId);
        return "contract/contract-debtor-form";
    }

    @Hidden
    @GetMapping("/contracts/{contractId}/approve/signature")
    public String contractDebtorSignaturePage(
            @PathVariable Long contractId,
            Model model
    ) {
        model.addAttribute("contractId", contractId);
        return "contract/contract-debtor-signature";
    }
}
