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
}