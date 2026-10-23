package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.client.RagSseReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("RagSseReader SSE 파싱 테스트")
class RagSseReaderTest {

    private List<String> read(String body) throws IOException {
        List<String> received = new ArrayList<>();

        RagSseReader.read(
                new BufferedReader(new StringReader(body)),
                new RagSseReader.Listener() {
                    @Override
                    public void onEvent(String event, String data) {
                        received.add(event + "|" + data);
                    }

                    @Override
                    public void onComment(String comment) {
                        received.add("#" + comment);
                    }
                }
        );

        return received;
    }

    @Test
    @DisplayName("event/data 묶음을 순서대로 읽고 keep-alive 주석을 구분한다")
    void readsEventsAndComments() throws IOException {
        String body = """
                event: progress
                data: {"step": "analysis"}

                : keep-alive

                event: draft
                data: {"suggestedClause": "제1조"}

                event: final
                data: {"clauseRevised": false}

                """;

        assertEquals(
                List.of(
                        "progress|{\"step\": \"analysis\"}",
                        "#keep-alive",
                        "draft|{\"suggestedClause\": \"제1조\"}",
                        "final|{\"clauseRevised\": false}"
                ),
                read(body)
        );
    }

    @Test
    @DisplayName("마지막 빈 줄 없이 끝나도 마지막 이벤트를 넘긴다")
    void readsLastEventWithoutTrailingBlankLine() throws IOException {
        assertEquals(
                List.of("final|{\"a\":1}"),
                read("event: final\ndata: {\"a\":1}")
        );
    }

    @Test
    @DisplayName("event 이름이 없으면 message로 본다")
    void defaultsEventNameToMessage() throws IOException {
        assertEquals(
                List.of("message|hello"),
                read("data: hello\n\n")
        );
    }
}