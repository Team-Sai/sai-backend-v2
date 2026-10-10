package org.teamsai.saibackend.domain.calendar.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.internal.PreparationAgentDraft;
import org.teamsai.saibackend.domain.calendar.dto.internal.PreparationPlanningContext;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationPlanningViolationResponse;
import org.teamsai.saibackend.domain.calendar.support.PreparationPlanningValidator;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SpringAiPreparationPlanningAgent
        implements PreparationPlanningAgent {

    private static final String SYSTEM_PROMPT = loadSystemPrompt();

    private static String loadSystemPrompt() {
        ClassPathResource resource = new ClassPathResource(
                "prompts/preparation-planning-system.txt"
        );

        try {
            String prompt = resource.getContentAsString(
                    StandardCharsets.UTF_8
            );

            if (prompt.isBlank()) {
                throw new IllegalStateException(
                        "Preparation planning system prompt is empty"
                );
            }

            return prompt;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to load preparation planning system prompt",
                    exception
            );
        }
    }

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final PreparationPlanningValidator validator;
    private final Clock clock;
    private final Duration requestTimeout;
    private final JsonMapper jsonMapper =
            JsonMapper.builder().build();

    public SpringAiPreparationPlanningAgent(
            ObjectProvider<ChatModel> chatModelProvider,
            PreparationPlanningValidator validator,
            @Qualifier("repaymentClock") Clock clock,
            @Value("${spring.ai.openai.timeout:PT1M}")
            Duration requestTimeout
    ) {
        this.chatModelProvider = chatModelProvider;
        this.validator = validator;
        this.clock = clock;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public PreparationAgentDraft generate(
            PreparationPlanningContext context,
            PreparationProposalRequest request,
            Instant now,
            PreparationAgentDraft previousDraft,
            List<PreparationPlanningViolationResponse> feedback
    ) {
        ChatModel model = chatModelProvider.getIfAvailable();

        if (model == null) {
            throw new IllegalStateException(
                    "Preparation planning AI is disabled"
            );
        }

        AgentInput input = new AgentInput(
                "Asia/Seoul",
                now,
                request,
                previousDraft,
                feedback
        );

        String inputJson =
                jsonMapper.writeValueAsString(input);

        PreparationPlanningTools tools =
                new PreparationPlanningTools(
                        context,
                        request,
                        validator,
                        clock
                );

        AtomicInteger toolRounds = new AtomicInteger();

        ToolCallingAdvisor toolAdvisor =
                ToolCallingAdvisor.builder()
                        .toolExecutionEligibilityChecker(response -> {
                            if (response == null || !response.hasToolCalls()) {
                                return false;
                            }

                            if (toolRounds.incrementAndGet() > 4) {
                                throw new IllegalStateException(
                                        "Preparation tool round limit exceeded"
                                );
                            }

                            return true;
                        })
                        .build();
        if (!(model.getOptions()
                instanceof OpenAiChatOptions defaultOptions)) {
            throw new IllegalStateException(
                    "Preparation planning requires an OpenAI chat model"
            );
        }

        ChatClient.create(model)
                .prompt()
                .options(
                        defaultOptions.mutate()
                                .timeout(requestTimeout)
                                .toolChoice("required")
                                .parallelToolCalls(false)
                )
                .system(SYSTEM_PROMPT)
                .user(
                        "상환 확인 일정을 제안하세요. "
                                + "먼저 readRepaymentFacts와 "
                                + "readFundingAndHistory를 각각 호출하세요. "
                                + "그다음 최종 후보 전체를 "
                                + "validateReminderDraft에 전달하세요. "
                                + "검증 도구 호출이 현재 작업의 마지막 단계입니다.\n"
                                + inputJson
                )
                .tools(tools)
                .advisors(toolAdvisor)
                .call()
                .content();

        return tools.getCheckedDraft();
    }

    public record AgentInput(
            String timeZone,
            Instant now,
            PreparationProposalRequest request,
            PreparationAgentDraft previousDraft,
            List<PreparationPlanningViolationResponse> feedback
    ) {
    }
}