package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.client.RagStreamEventFilter;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RagStreamEventFilter 이벤트·필드 화이트리스트 테스트")
class RagStreamEventFilterTest {

    @Test
    @DisplayName("final 이벤트에서 debug 같은 내부 필드를 뺀다")
    void finalDropsDebug() {
        Optional<String> result = RagStreamEventFilter.filter(
                "final",
                "{\"suggestedClause\":\"제1조\",\"clauseRevised\":false,"
                        + "\"debug\":{\"searchPlans\":[\"내부\"]}}"
        );

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("\"suggestedClause\":\"제1조\""));
        assertTrue(result.get().contains("\"clauseRevised\":false"));
        assertFalse(result.get().contains("debug"));
    }

    @Test
    @DisplayName("delta 이벤트는 text만 남긴다")
    void deltaKeepsText() {
        assertEquals(
                Optional.of("{\"text\":\"채무자는 \"}"),
                RagStreamEventFilter.filter("delta", "{\"text\":\"채무자는 \",\"raw\":\"x\"}")
        );
    }

    @Test
    @DisplayName("모르는 이벤트와 JSON이 아닌 데이터는 보내지 않는다")
    void unknownOrBrokenIsDropped() {
        assertTrue(RagStreamEventFilter.filter("internal", "{\"a\":1}").isEmpty());
        assertTrue(RagStreamEventFilter.filter("progress", "not-json").isEmpty());
        assertTrue(RagStreamEventFilter.filter("progress", "[1,2]").isEmpty());
    }

    @Test
    @DisplayName("draft·final은 references·validation 같은 중첩 객체 안의 모르는 필드도 뺀다")
    void nestedUnknownFieldsAreDropped() {
        String data = """
                {
                  "suggestedClause": "제1조",
                  "validation": {
                    "status": "PASS",
                    "isSafeToSuggest": true,
                    "checks": [{"type": "AMBIGUITY", "status": "PASS", "message": "ok", "llmReason": "내부 판단"}],
                    "internalScore": 0.42
                  },
                  "references": [{"lawName": "민법", "articleNumber": "제468조", "searchQuery": "내부 검색 질의"}],
                  "clauseRevised": true
                }
                """;

        String result = RagStreamEventFilter.filter("final", data).orElseThrow();

        assertTrue(result.contains("\"lawName\":\"민법\""));
        assertTrue(result.contains("\"isSafeToSuggest\":true"));
        assertTrue(result.contains("\"clauseRevised\":true"));
        assertFalse(result.contains("llmReason"));
        assertFalse(result.contains("internalScore"));
        assertFalse(result.contains("searchQuery"));
    }

    @Test
    @DisplayName("AI가 보내지 않은 필드는 null로 만들어 보내지 않는다")
    void absentFieldsStayAbsent() {
        String result = RagStreamEventFilter.filter(
                "draft",
                "{\"title\":\"중도상환\",\"suggestedClause\":\"제1조\"}"
        ).orElseThrow();

        assertFalse(result.contains("references"));
        assertFalse(result.contains("null"));
    }

    @Test
    @DisplayName("clauseRevised가 true/false가 아니면 빼고 보낸다")
    void nonBooleanClauseRevisedIsDropped() {
        String result = RagStreamEventFilter.filter(
                "final",
                "{\"suggestedClause\":\"제1조\",\"clauseRevised\":\"yes\"}"
        ).orElseThrow();

        assertFalse(result.contains("clauseRevised"));
    }

    @Test
    @DisplayName("final·error만 스트림을 끝내는 이벤트로 본다")
    void terminalEvents() {
        assertTrue(RagStreamEventFilter.isTerminal("final"));
        assertTrue(RagStreamEventFilter.isTerminal("error"));
        assertFalse(RagStreamEventFilter.isTerminal("draft"));
        assertFalse(RagStreamEventFilter.isTerminal("delta"));
    }

    @Test
    @DisplayName("references 이벤트는 근거 법령 목록만, DTO에 없는 내부 필드는 빼고 보낸다")
    void referencesEventKeepsOnlyKnownReferenceFields() {
        String result = RagStreamEventFilter.filter(
                "references",
                "{\"references\":[{\"lawName\":\"민법\",\"articleNumber\":\"제468조\",\"searchQuery\":\"내부\"}],"
                        + "\"debug\":{\"plans\":[]}}"
        ).orElseThrow();

        assertTrue(result.contains("\"lawName\":\"민법\""));
        assertTrue(result.contains("\"articleNumber\":\"제468조\""));
        assertFalse(result.contains("searchQuery"));
        assertFalse(result.contains("debug"));
        assertFalse(RagStreamEventFilter.isTerminal("references"));
    }
}