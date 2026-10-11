package org.teamsai.saibackend.domain.contract.service;

import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;

public interface RepaymentAgent {

    RepaymentAgentDraft generate(RepaymentAnalysisContext context);
}