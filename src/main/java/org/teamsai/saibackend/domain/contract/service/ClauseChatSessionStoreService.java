package org.teamsai.saibackend.domain.contract.service;

import java.time.Duration;
import java.util.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.dto.ClauseChatSessionDTO;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

/**
 * 대화형 AI 특약의 대화 상태를 Redis에 보관한다.
 *
 * 키: clause-chat:{sessionId}, 값: JSON, 30분 뒤 자동 삭제 (저장할 때마다 다시 30분)
 * 백엔드가 여러 대여도 같은 대화를 이어갈 수 있도록 서버 메모리가 아닌 Redis에 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClauseChatSessionStoreService {

    private static final String KEY_PREFIX = "clause-chat:";

    static final Duration TTL = Duration.ofMinutes(30);

    private static final String LOCK_KEY_PREFIX = "clause-chat:lock:";

    private static final String REFERENCES_KEY_PREFIX = "clause-chat:refs:";

    /** 근거 법령 목록 JSON을 읽을 때 쓰는 타입. 매번 만들지 않도록 한 번만 만든다. */
    private static final TypeReference<List<Map<String, Object>>> REFERENCES_TYPE =
            new TypeReference<List<Map<String, Object>>>() {};

    /** 잠금은 AI 답장 분류 시간보다 넉넉하게. 서버가 죽어도 이 시간이 지나면 저절로 풀린다. */
    static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper;

    private final StringRedisTemplate redisTemplate;

    /** 새 대화 번호를 만들어 저장하고 번호를 돌려준다. */
    public String create(ClauseChatSessionDTO session) {
        String sessionId = UUID.randomUUID().toString();
        save(sessionId, session);
        return sessionId;
    }

    public void save(String sessionId, ClauseChatSessionDTO session) {
        redisTemplate.opsForValue().set(
                createKey(sessionId),
                objectMapper.writeValueAsString(session),
                TTL
        );
    }

    /**
     * 대화가 아직 있을 때만 덮어쓴다. (Redis SET XX)
     * AI를 부르는 사이 30분이 지나 대화가 사라졌으면 되살리지 않고 false.
     */
    public boolean saveIfPresent(String sessionId, ClauseChatSessionDTO session) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfPresent(
                        createKey(sessionId),
                        objectMapper.writeValueAsString(session),
                        TTL
                )
        );
    }

    /**
     * 같은 대화에 요청이 겹치지 않게 짧게 잠근다. (Redis SET NX)
     * 이미 잠겨 있으면 false.
     */
    public boolean tryLock(String sessionId) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(
                        LOCK_KEY_PREFIX + sessionId,
                        "1",
                        LOCK_TTL
                )
        );
    }

    public void unlock(String sessionId) {
        redisTemplate.delete(LOCK_KEY_PREFIX + sessionId);
    }

    /**
     * 미리 찾은 근거 법령을 대화와 다른 키에 둔다.
     * 같은 키에 두면 답장 저장(대화 전체 덮어쓰기)이 방금 넣은 법령을 지울 수 있다.
     */
    public void saveReferences(String sessionId, List<Map<String, Object>> references) {
        redisTemplate.opsForValue().set(
                REFERENCES_KEY_PREFIX + sessionId,
                objectMapper.writeValueAsString(references),
                TTL
        );
    }

    /**
     * 미리 찾은 법령을 꺼내면서 지운다. (작성 때 한 번 쓰면 더 필요 없다)
     * 없거나(아직 안 끝남·실패·만료) 깨졌으면 빈 값. 그러면 AI가 지금처럼 검색한다.
     */
    public Optional<List<Map<String, Object>>> takeReferences(String sessionId) {
        String json = redisTemplate.opsForValue().getAndDelete(REFERENCES_KEY_PREFIX + sessionId);

        if (json == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(json, REFERENCES_TYPE));

        } catch (JacksonException e) {
            log.warn("대화형 특약 근거 법령 읽기 실패 - exceptionType={}", e.getClass().getName());
            return Optional.empty();
        }
    }

    /**
     * 대화가 없거나, 30분이 지났거나, 다른 사용자의 대화면 빈 값.
     * (다른 사용자의 대화가 "있다"는 사실도 알리지 않도록 셋을 구분하지 않는다)
     */
    public Optional<ClauseChatSessionDTO> find(String sessionId, Long userId) {
        String json = redisTemplate.opsForValue().get(createKey(sessionId));

        if (json == null) {
            return Optional.empty();
        }

        ClauseChatSessionDTO session;

        try {
            session = objectMapper.readValue(json, ClauseChatSessionDTO.class);

        } catch (JacksonException e) {
            // 저장된 값이 깨졌으면 이어갈 수 없으니 새 대화를 시작하게 한다.
            log.warn("대화형 특약 상태 읽기 실패 - exceptionType={}", e.getClass().getName());
            return Optional.empty();
        }

        if (!Objects.equals(session.getOwnerUserId(), userId)) {
            return Optional.empty();
        }

        return Optional.of(session);
    }

    private String createKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}