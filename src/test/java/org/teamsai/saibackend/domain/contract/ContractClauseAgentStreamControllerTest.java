package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.teamsai.saibackend.domain.contract.controller.ContractClauseAgentController;
import org.teamsai.saibackend.domain.contract.service.ContractClauseAgentService;
import org.teamsai.saibackend.global.config.SecurityConfig;
import org.teamsai.saibackend.global.jwt.JwtAuthenticationEntryPoint;
import org.teamsai.saibackend.global.jwt.JwtAuthenticationFilter;
import org.teamsai.saibackend.global.jwt.JwtTokenProvider;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractClauseAgentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        ContractClauseAgentStreamControllerTest.TestSecurity.class})
@DisplayName("AI 특약 스트리밍 API 보안·중계 테스트")
class ContractClauseAgentStreamControllerTest {

    private static final String STREAM_URL = "/api/contracts/clause-suggestions/stream";
    private static final String BODY =
            "{\"principalAmount\":1000000,\"interestRate\":5,\"message\":\"이자 5% 조항 만들어줘\"}";

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestSecurity {
    }

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    ContractClauseAgentService service;

    @MockitoBean
    JwtTokenProvider tokens;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("토큰 없이 부르면 401이고 AI를 호출하지 않는다 (ASYNC 허용이 처음 요청까지 열지 않는다)")
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("로그인하면 스트림이 열리고, ASYNC 디스패치에서도 403 없이 이벤트가 전달된다")
    void authenticatedRequestStreamsEvents() throws Exception {
        authenticate();
        SseEmitter emitter = new SseEmitter(5_000L);
        when(service.streamClause(any())).thenReturn(emitter);

        MvcResult started = mvc.perform(post(STREAM_URL)
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        emitter.send(SseEmitter.event().name("progress").data("{\"step\":\"analysis\"}"));
        emitter.complete();

        // 응답을 마무리하는 ASYNC 디스패치. SecurityConfig의 ASYNC permitAll이 없으면 여기서 막힌다.
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:progress")));
    }

    @Test
    @DisplayName("요청이 몰려 서비스가 503을 던지면 스트림을 열지 않고 503으로 응답한다")
    void overloadedReturns503() throws Exception {
        authenticate();
        when(service.streamClause(any())).thenThrow(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "지금 AI 요청이 많아요. 잠시 후 다시 시도해 주세요."
        ));

        mvc.perform(post(STREAM_URL)
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    private void authenticate() {
        when(tokens.getUserIdIfValid("test-token")).thenReturn(Optional.of(42L));
    }
}