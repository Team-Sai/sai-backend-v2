package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.repository.RiskCheckRepository;

@Service
@RequiredArgsConstructor
public class RiskCheckService {

    private final RiskCheckRepository riskCheckRepository;

    public Long getPreviousTotalAmount(Long userId) {
        return riskCheckRepository.sumCompletedPrincipalByCreditor(userId);
    }
}
