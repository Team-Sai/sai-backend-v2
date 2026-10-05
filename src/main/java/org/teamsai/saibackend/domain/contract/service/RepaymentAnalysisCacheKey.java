package org.teamsai.saibackend.domain.contract.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Component
public class RepaymentAnalysisCacheKey {

    private final String provider;
    private final String model;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public RepaymentAnalysisCacheKey(
            @Value("${spring.ai.model.chat:none}") String provider,
            @Value("${spring.ai.google.genai.chat.model:disabled}") String model
    ) {
        this.provider = provider;
        this.model = model;
    }

    public String create(
            Long userId,
            RepaymentAnalysisContext context
    ) {
        List<CandidateSnapshot> candidates = context.candidates().stream()
                .map(candidate -> new CandidateSnapshot(
                        candidate.contractId(),
                        candidate.scheduleId(),
                        candidate.contractName(),
                        candidate.dueDate().toString(),
                        amount(candidate.remainingAmount()),
                        candidate.pastDue()
                ))
                .toList();

        Snapshot snapshot = new Snapshot(
                provider,
                model,
                SpringAiRepaymentAgent.PROMPT_VERSION,
                context.analysisDate().toString(),
                context.targetMonth().toString(),
                amount(context.payableThisMonthAmount()),
                amount(context.overdueAmount()),
                amount(context.totalRequiredAmount()),
                amount(context.totalRemainingAmount()),
                candidates
        );

        String json = mapper.writeValueAsString(snapshot);

        return "repayment:analysis:v2:{"
                + userId
                + ":"
                + sha256(json)
                + "}";
    }

    public String failureScope(Long userId) {
        String configuration = mapper.writeValueAsString(List.of(
                provider,
                model,
                SpringAiRepaymentAgent.PROMPT_VERSION
        ));

        return "repayment:failure:"
                + userId
                + ":"
                + sha256(configuration);
    }

    private String amount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }

    public record Snapshot(
            String provider,
            String model,
            String promptVersion,
            String analysisDate,
            String targetMonth,
            String payableThisMonthAmount,
            String overdueAmount,
            String totalRequiredAmount,
            String totalRemainingAmount,
            List<CandidateSnapshot> candidates
    ) {
    }

    public record CandidateSnapshot(
            Long contractId,
            Long scheduleId,
            String contractName,
            String dueDate,
            String remainingAmount,
            boolean pastDue
    ) {
    }
}