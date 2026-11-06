package org.teamsai.saibackend.domain.contract;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.teamsai.saibackend.domain.contract.dto.ClauseChatSessionDTO;
import org.teamsai.saibackend.domain.contract.service.ClauseChatSessionStoreService;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClauseChatSessionStore 단위 테스트")
class ClauseChatSessionStoreServiceTest {

    private static final Long OWNER_ID = 1L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Spy
    private ObjectMapper objectMapper = JsonMapper.builder().build();

    @InjectMocks
    private ClauseChatSessionStoreService store;

    private ClauseChatSessionDTO session() {
        return ClauseChatSessionDTO.builder()
                .ownerUserId(OWNER_ID)
                .message("3개월 연속 연체하면 전부 갚게 해 주세요")
                .issues(List.of(Map.of("type", "DEFAULT")))
                .slots(List.of(Map.of("name", "연속 연체 기간", "value", "3개월")))
                .questions(List.of())
                .answers(Map.of())
                .build();
    }

    /** create()로 저장된 JSON을 꺼내 온다. */
    private String savedJson(String sessionId) {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("clause-chat:" + sessionId), json.capture(), eq(Duration.ofMinutes(30)));
        return json.getValue();
    }

    @Test
    @DisplayName("새 대화를 30분 TTL로 저장하고 번호를 돌려준다")
    void createSavesWithTtl() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        String sessionId = store.create(session());

        assertThat(sessionId).isNotBlank();
        assertThat(savedJson(sessionId)).contains("\"ownerUserId\":1");
    }

    @Test
    @DisplayName("저장한 대화를 주인이 다시 읽을 수 있다")
    void ownerCanRead() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        String sessionId = store.create(session());
        String json = savedJson(sessionId);
        given(valueOperations.get("clause-chat:" + sessionId)).willReturn(json);

        Optional<ClauseChatSessionDTO> found = store.find(sessionId, OWNER_ID);

        assertThat(found).isPresent();
        assertThat(found.get().getSlots().get(0).get("value")).isEqualTo("3개월");
        assertThat(found.get().isReadyToWrite()).isFalse();
    }

    @Test
    @DisplayName("다른 사용자의 대화는 없는 것처럼 빈 값")
    void otherUserCannotRead() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        String sessionId = store.create(session());
        String json = savedJson(sessionId);
        given(valueOperations.get("clause-chat:" + sessionId)).willReturn(json);

        assertThat(store.find(sessionId, 2L)).isEmpty();
    }

    @Test
    @DisplayName("없거나 시간이 지난 대화는 빈 값")
    void missingSessionIsEmpty() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(anyString())).willReturn(null);

        assertThat(store.find("no-such-session", OWNER_ID)).isEmpty();
    }

    @Test
    @DisplayName("깨진 값은 빈 값으로 처리한다")
    void brokenJsonIsEmpty() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(anyString())).willReturn("{깨진 값");

        assertThat(store.find("broken", OWNER_ID)).isEmpty();
    }

    @Test
    @DisplayName("만료된 대화는 되살리지 않는다 (SET XX)")
    void saveIfPresentDoesNotReviveExpiredSession() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfPresent(eq("clause-chat:gone"), anyString(), eq(Duration.ofMinutes(30))))
                .willReturn(false);

        assertThat(store.saveIfPresent("gone", session())).isFalse();
    }

    @Test
    @DisplayName("이미 잠긴 대화는 잠글 수 없다 (SET NX)")
    void tryLockFailsWhenAlreadyLocked() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(eq("clause-chat:lock:session-1"), any(), any(Duration.class)))
                .willReturn(true, false);

        assertThat(store.tryLock("session-1")).isTrue();
        assertThat(store.tryLock("session-1")).isFalse();
    }

    @Test
    @DisplayName("미리 찾은 법령은 꺼내면서 지운다")
    void takeReferencesReadsAndDeletes() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete("clause-chat:refs:session-1"))
                .willReturn("[{\"document_id\":\"민법-398\"}]");

        Optional<List<Map<String, Object>>> references = store.takeReferences("session-1");

        assertThat(references).isPresent();
        assertThat(references.get().get(0)).containsEntry("document_id", "민법-398");
        verify(valueOperations, never()).get("clause-chat:refs:session-1");
    }
}