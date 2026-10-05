package org.teamsai.saibackend.global.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "spring.ai.model.chat",
        havingValue = "google-genai",
        matchIfMissing = false
)
public class RepaymentGeminiClientConfig {

    @Bean(destroyMethod = "close")
    public Client googleGenAiClient(
            @Value("${spring.ai.google.genai.api-key:}")
            String apiKey,
            @Value("${sai.repayment.ai-request-timeout:PT15S}")
            Duration timeout
    ) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException(
                    "Gemini API key must be configured");
        }

        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(requestOptions(timeout))
                .build();
    }

    public static HttpOptions requestOptions(Duration timeout) {
        long milliseconds = timeout.toMillis();

        if (milliseconds <= 0
                || milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Invalid Gemini request timeout");
        }

        return HttpOptions.builder()
                .timeout((int) milliseconds)
                .retryOptions(
                        HttpRetryOptions.builder()
                                // 최초 요청을 포함한 총 시도 횟수.
                                .attempts(1)
                                .build()
                )
                .build();
    }
}