package org.teamsai.saibackend.domain.contract.controller;


import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;


@Controller
public class ContractDetailController {

    @Hidden
    @GetMapping("/contracts/{contractId}/contract-detail")
    public String detailResponsePage(
            @PathVariable Long contractId,
            Model model
    ) {
        model.addAttribute("contractId", contractId);
        return "contract/detail";
    }
}

