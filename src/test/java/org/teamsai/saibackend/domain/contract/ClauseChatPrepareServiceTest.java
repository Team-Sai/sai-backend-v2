package org.teamsai.saibackend.domain.contract;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseChatRequest;
import org.teamsai.saibackend.domain.contract.dto.response.RagClausePrepareResponse;
import org.teamsai.saibackend.domain.contract.service.ClauseChatPrepareService;
import org.teamsai.saibackend.domain.contract.service.ClauseChatSessionStoreService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClauseChatPrepareService 단위 테스트")
class ClauseChatPrepareServiceTest {

    @Mock
    private RagApiClient ragApiClient;

    @Mock
    private ClauseChatSessionStoreService sessionStore;

    @InjectMocks
    private ClauseChatPrepareService prepareService;

    private final RagClauseChatRequest request = RagClauseChatRequest.builder()
            .message("위약금을 정해 주세요")
            .issues(List.of(Map.of("type", "PENALTY")))
            .build();

    @Test
    @DisplayName("미리 찾은 법령을 대화 번호로 보관한다")
    void savesPreparedReferences() {
        List<Map<String, Object>> references = List.of(Map.of("document_id", "민법-398"));
        given(ragApiClient.prepareReferences(request))
                .willReturn(new RagClausePrepareResponse(references));

        prepareService.prepare("session-1", request);

        verify(sessionStore).saveReferences("session-1", references);
    }

    @Test
    @DisplayName("AI 오류가 나도 예외를 밖으로 던지지 않고 보관도 하지 않는다")
    void failureIsSwallowed() {
        given(ragApiClient.prepareReferences(request))
                .willThrow(new RuntimeException("AI 서버 오류"));

        assertThatCode(() -> prepareService.prepare("session-1", request))
                .doesNotThrowAnyException();
        verify(sessionStore, never()).saveReferences(any(), any());
    }

    @Test
    @DisplayName("찾은 법령이 없으면 보관하지 않는다")
    void emptyIsNotSaved() {
        given(ragApiClient.prepareReferences(request))
                .willReturn(new RagClausePrepareResponse(List.of()));

        prepareService.prepare("session-1", request);

        verify(sessionStore, never()).saveReferences(any(), any());
    }
}