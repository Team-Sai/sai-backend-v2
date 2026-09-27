package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.settlement.controller.*;
import org.teamsai.saibackend.domain.settlement.repository.*;
import org.teamsai.saibackend.domain.settlement.service.*;
import org.teamsai.saibackend.domain.settlement.support.*;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.exception.GlobalExceptionHandler;
import org.teamsai.saibackend.global.security.CustomUserDetails;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SettlementCreationValidationControllerTest {
    private MockMvc mvc;
    private UserRepository userRepository;
    private SettlementRepository settlementRepository;
    private RecurringSettlementRepository recurringRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        settlementRepository = mock(SettlementRepository.class);
        recurringRepository = mock(RecurringSettlementRepository.class);
        SettlementParticipantValidator participants = new SettlementParticipantValidator();
        SettlementAccountService accounts = mock(SettlementAccountService.class);
        SettlementParticipantService registration = mock(SettlementParticipantService.class);
        SettlementAmountCalculator calculator = new SettlementAmountCalculator();
        SharedSettlementService shared = new SharedSettlementService(accounts,
                new SettlementValidator(mock(LinkedBankAccountService.class),
                        mock(SettlementParticipantRepository.class), participants),
                registration, calculator, settlementRepository, userRepository);
        RecurringSettlementService recurring = new RecurringSettlementService(recurringRepository,
                settlementRepository, new RecurringSettlementValidator(participants), calculator,
                registration, accounts, userRepository);
        CustomUserDetails principal = mock(CustomUserDetails.class);
        when(principal.getUserId()).thenReturn(1L);

        mvc = MockMvcBuilders.standaloneSetup(
                        new SettlementController(shared, mock(SettlementCloseService.class),
                                mock(SettlementQueryService.class), mock(SettlementSummaryQueryService.class)),
                        new RecurringSettlementController(recurring))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == CustomUserDetails.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
                        return principal;
                    }
                })
                .build();
    }

    static Stream<Arguments> invalidParticipants() {
        return Stream.of("shared", "recurring").flatMap(type -> Stream.of(
                Arguments.of(type, "", "SETTLEMENT_PARTICIPANT_REQUIRED"),
                Arguments.of(type, ",\"participants\":null", "SETTLEMENT_PARTICIPANT_REQUIRED"),
                Arguments.of(type, ",\"participants\":[]", "SETTLEMENT_PARTICIPANT_REQUIRED"),
                Arguments.of(type, ",\"participants\":[null]", "INVALID_SETTLEMENT_PARTICIPANT"),
                Arguments.of(type, ",\"participants\":[{}]", "INVALID_SETTLEMENT_PARTICIPANT"),
                Arguments.of(type, ",\"participants\":[{\"userToken\":\"\"}]", "INVALID_SETTLEMENT_PARTICIPANT"),
                Arguments.of(type, ",\"participants\":[{\"userToken\":\"   \"}]", "INVALID_SETTLEMENT_PARTICIPANT"),
                Arguments.of(type, ",\"participants\":[{\"userToken\":\"A\"},{\"userToken\":\"A\"}]",
                        "DUPLICATE_SETTLEMENT_PARTICIPANT")
        ));
    }

    @ParameterizedTest
    @MethodSource("invalidParticipants")
    void returnsCommonValidatorErrorThroughApi(String type, String participants, String code) throws Exception {
        mvc.perform(post("/api/settlements/" + type)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(type, "test", participants)))
                .andExpect(status().is(code.equals("DUPLICATE_SETTLEMENT_PARTICIPANT") ? 409 : 400))
                .andExpect(jsonPath("$.code").value(code));
        verifyNoInteractions(userRepository, settlementRepository, recurringRepository);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"shared", "recurring"})
    void retainsValidationForOtherRequestFields(String type) throws Exception {
        mvc.perform(post("/api/settlements/" + type)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(type, "", ",\"participants\":[{\"userToken\":\"A\"}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(type.equals("shared")
                        ? "정산명을 입력해주세요." : "정산명을 입력해 주세요."));
        verifyNoInteractions(userRepository, settlementRepository, recurringRepository);
    }

    private String request(String type, String title, String participants) {
        String date = LocalDate.now().plusDays(1).toString();
        String schedule = type.equals("shared") ? "\"dueDate\":\"" + date + "\""
                : "\"startDate\":\"" + date + "\",\"cycleRule\":\"MONTHLY\"";
        return "{\"settlementCategory\":\"test\",\"title\":\"" + title
                + "\",\"totalAmount\":1000,\"linkedAccountId\":1," + schedule + participants + "}";
    }
}
