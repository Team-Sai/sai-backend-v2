package org.teamsai.saibackend.domain.contract;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.client.RagSseReader;
import org.teamsai.saibackend.domain.contract.dto.ClauseChatSessionDTO;
import org.teamsai.saibackend.domain.contract.dto.request.ClauseChatReplyRequest;
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseChatRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ClauseChatReplyResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ClauseChatStartResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RagClauseRouteResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RagClauseUnderstandResponse;
import org.teamsai.saibackend.domain.contract.exception.ClauseChatErrorCode;
import org.teamsai.saibackend.domain.contract.service.ClauseChatPrepareService;
import org.teamsai.saibackend.domain.contract.service.ClauseChatService;
import org.teamsai.saibackend.domain.contract.service.ClauseChatSessionStoreService;
import org.teamsai.saibackend.domain.contract.service.ContractClauseAgentService;
import org.teamsai.saibackend.global.exception.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClauseChatService 단위 테스트")
class ClauseChatServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private RagApiClient ragApiClient;

    @Mock
    private ClauseChatSessionStoreService sessionStore;

    @Mock
    private ContractClauseAgentService contractClauseAgentService;

    @Mock
    private ClauseChatPrepareService prepareService;

    @InjectMocks
    private ClauseChatService clauseChatService;

    private final ContractClauseRequest request = ContractClauseRequest.builder()
            .message("3개월 연속 연체하면 전부 갚게 해 주세요")
            .build();

    private final RagClauseSuggestionRequest ragRequest = RagClauseSuggestionRequest.builder()
            .message("3개월 연속 연체하면 전부 갚게 해 주세요")
            .build();

    private RagClauseUnderstandResponse understood(boolean inScope, List<Map<String, Object>> fieldEdits) {
        return new RagClauseUnderstandResponse(
                inScope,
                List.of(Map.of("type", "DEFAULT")),
                Map.of("summary", "요약", "slots", List.of(Map.of("name", "연속 연체 기간", "value", "3개월"))),
                List.of(Map.of("id", "partial_payment", "text", "일부만 낸 달도 연체로 볼까요?")),
                fieldEdits,
                inScope ? null : "범위 밖 안내",
                List.of("보증은 서면으로 해야 효력이 있어요 (민법 제428조의2제1항)")
        );
    }

    @Test
    @DisplayName("이어갈 수 있는 요청이면 대화를 저장하고 번호를 준다")
    void startCreatesSession() {
        given(contractClauseAgentService.toRagRequest(request)).willReturn(ragRequest);
        given(ragApiClient.understandClause(ragRequest)).willReturn(understood(true, List.of()));
        given(sessionStore.create(any())).willReturn("session-1");

        ClauseChatStartResponse response = clauseChatService.start(USER_ID, request);

        ArgumentCaptor<ClauseChatSessionDTO> saved = ArgumentCaptor.forClass(ClauseChatSessionDTO.class);
        verify(sessionStore).create(saved.capture());

        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.summary()).isEqualTo("요약");
        assertThat(saved.getValue().getOwnerUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getIssues()).hasSize(1);
        assertThat(response.notices()).hasSize(1);
        verify(prepareService).prepareInBackground(eq("session-1"), any());
    }

    @Test
    @DisplayName("범위 밖 요청은 대화를 만들지 않는다")
    void outOfScopeCreatesNoSession() {
        given(contractClauseAgentService.toRagRequest(request)).willReturn(ragRequest);
        given(ragApiClient.understandClause(ragRequest)).willReturn(understood(false, List.of()));

        ClauseChatStartResponse response = clauseChatService.start(USER_ID, request);

        assertThat(response.sessionId()).isNull();
        assertThat(response.guide()).isEqualTo("범위 밖 안내");
        verify(sessionStore, never()).create(any());
        verify(prepareService, never()).prepareInBackground(any(), any());
    }

    @Test
    @DisplayName("계약서 칸을 고치라는 안내면 대화를 만들지 않는다")
    void fieldEditCreatesNoSession() {
        given(contractClauseAgentService.toRagRequest(request)).willReturn(ragRequest);
        given(ragApiClient.understandClause(ragRequest))
                .willReturn(understood(true, List.of(Map.of("field", "interestRate"))));

        ClauseChatStartResponse response = clauseChatService.start(USER_ID, request);

        assertThat(response.sessionId()).isNull();
        assertThat(response.fieldEdits()).hasSize(1);
        verify(sessionStore, never()).create(any());
    }

    @Test
    @DisplayName("없거나 남의 대화에 답하면 404")
    void replyToMissingSessionIs404() {
        given(sessionStore.tryLock("session-1")).willReturn(true);
        given(sessionStore.find("session-1", USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> clauseChatService.reply(USER_ID, "session-1", new ClauseChatReplyRequest("네")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(ClauseChatErrorCode.CHAT_NOT_FOUND);
    }

    @Test
    @DisplayName("답장 결과를 대화에 저장하고 돌려준다")
    void replySavesRoutedState() {
        ClauseChatSessionDTO session = ClauseChatSessionDTO.builder()
                .ownerUserId(USER_ID)
                .slots(List.of())
                .questions(List.of())
                .answers(Map.of())
                .build();

        given(sessionStore.tryLock("session-1")).willReturn(true);
        given(sessionStore.saveIfPresent("session-1", session)).willReturn(true);
        given(sessionStore.find("session-1", USER_ID)).willReturn(Optional.of(session));
        given(ragApiClient.routeReply(any())).willReturn(
                new RagClauseRouteResponse(
                        "CONFIRM",
                        List.of(Map.of("name", "연속 연체 기간", "confirmed", true)),
                        Map.of(),
                        List.of(),
                        "확인했어요.",
                        true
                )
        );

        ClauseChatReplyResponse response =
                clauseChatService.reply(USER_ID, "session-1", new ClauseChatReplyRequest(" 네 "));

        assertThat(response.kind()).isEqualTo("CONFIRM");
        assertThat(response.readyToWrite()).isTrue();
        assertThat(session.isReadyToWrite()).isTrue();
        verify(sessionStore).unlock("session-1");
    }

    @Test
    @DisplayName("확정 전에는 작성할 수 없다 (409)")
    void writeBeforeConfirmIsRejected() {
        given(sessionStore.tryLock("session-1")).willReturn(true);
        given(sessionStore.find("session-1", USER_ID)).willReturn(Optional.of(
                ClauseChatSessionDTO.builder().ownerUserId(USER_ID).readyToWrite(false).build()
        ));

        assertThatThrownBy(() -> clauseChatService.write(USER_ID, "session-1"))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(ClauseChatErrorCode.CHAT_NOT_READY_TO_WRITE);

        verify(contractClauseAgentService, never()).relayStream(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("확정했으면 저장한 쟁점과 확정값으로 작성 스트림을 연다")
    void writeAfterConfirmRelaysWithSavedState() {
        ClauseChatSessionDTO session = ClauseChatSessionDTO.builder()
                .ownerUserId(USER_ID)
                .message("3개월 연속 연체하면 전부 갚게 해 주세요")
                .issues(List.of(Map.of("type", "DEFAULT")))
                .slots(List.of())
                .questions(List.of())
                .answers(Map.of())
                .readyToWrite(true)
                .build();
        SseEmitter emitter = new SseEmitter();

        given(sessionStore.tryLock("session-1")).willReturn(true);
        given(sessionStore.saveIfPresent("session-1", session)).willReturn(true);
        given(sessionStore.find("session-1", USER_ID)).willReturn(Optional.of(session));
        given(contractClauseAgentService.relayStream(any())).willReturn(emitter);
        given(sessionStore.findReferences("session-1"))
                .willReturn(Optional.of(List.of(Map.of("document_id", "민법-398"))));

        assertThat(clauseChatService.write(USER_ID, "session-1")).isSameAs(emitter);

        // relayStream에 넘긴 "AI 부르기"를 꺼내 실제로 실행해 보고,
        // 저장해 둔 쟁점과 처음 요청이 AI 요청에 들어가는지 확인한다.
        ArgumentCaptor<Consumer<RagSseReader.Listener>> aiCall = ArgumentCaptor.forClass(Consumer.class);
        verify(contractClauseAgentService).relayStream(aiCall.capture());

        RagSseReader.Listener listener = mock(RagSseReader.Listener.class);
        aiCall.getValue().accept(listener);

        ArgumentCaptor<RagClauseChatRequest> sent = ArgumentCaptor.forClass(RagClauseChatRequest.class);
        verify(ragApiClient).streamWrite(sent.capture(), eq(listener));

        assertThat(sent.getValue().getIssues()).hasSize(1);
        assertThat(sent.getValue().getMessage()).isEqualTo("3개월 연속 연체하면 전부 갚게 해 주세요");
        assertThat(sent.getValue().getReferences()).hasSize(1);
        assertThat(session.isReadyToWrite()).isFalse();
    }

    @Test
    @DisplayName("작성 요청이 몰려 시작하지 못하면 다시 작성할 수 있게 되돌린다")
    void rejectedWriteRestoresReadyToWrite() {
        ClauseChatSessionDTO session = ClauseChatSessionDTO.builder()
                .ownerUserId(USER_ID)
                .message("3개월 연속 연체하면 전부 갚게 해 주세요")
                .issues(List.of(Map.of("type", "DEFAULT")))
                .slots(List.of())
                .questions(List.of())
                .answers(Map.of())
                .readyToWrite(true)
                .build();

        given(sessionStore.tryLock("session-1")).willReturn(true);
        given(sessionStore.find("session-1", USER_ID)).willReturn(Optional.of(session));
        given(sessionStore.saveIfPresent("session-1", session)).willReturn(true);
        given(contractClauseAgentService.relayStream(any()))
                .willThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "지금 AI 요청이 많아요."));

        assertThatThrownBy(() -> clauseChatService.write(USER_ID, "session-1"))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(session.isReadyToWrite()).isTrue();
        verify(sessionStore, times(2)).saveIfPresent("session-1", session);
    }

    @Test
    @DisplayName("같은 대화에 답장이 겹치면 두 번째는 409이고 AI를 부르지 않는다")
    void concurrentReplyIsRejected() {
        given(sessionStore.tryLock("session-1")).willReturn(false);

        assertThatThrownBy(() -> clauseChatService.reply(USER_ID, "session-1", new ClauseChatReplyRequest("네")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(ClauseChatErrorCode.CHAT_BUSY);

        verify(ragApiClient, never()).routeReply(any());
        verify(sessionStore, never()).unlock(any());
    }

    @Test
    @DisplayName("AI를 부르는 사이 대화가 만료되면 되살리지 않고 404")
    void expiredDuringReplyIsNotRevived() {
        ClauseChatSessionDTO session = ClauseChatSessionDTO.builder()
                .ownerUserId(USER_ID)
                .slots(List.of())
                .questions(List.of())
                .answers(Map.of())
                .build();

        given(sessionStore.tryLock("session-1")).willReturn(true);
        given(sessionStore.find("session-1", USER_ID)).willReturn(Optional.of(session));
        given(ragApiClient.routeReply(any())).willReturn(
                new RagClauseRouteResponse("CONFIRM", List.of(), Map.of(), List.of(), "확인했어요.", true)
        );
        given(sessionStore.saveIfPresent("session-1", session)).willReturn(false);

        assertThatThrownBy(() -> clauseChatService.reply(USER_ID, "session-1", new ClauseChatReplyRequest("네")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(ClauseChatErrorCode.CHAT_NOT_FOUND);

        verify(sessionStore).unlock("session-1");
    }
}