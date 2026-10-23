package org.teamsai.saibackend.domain.contract.client;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * SSE(Server-Sent Events) 응답을 한 줄씩 읽어 이벤트 단위로 넘겨준다.
 *
 * <pre>
 * event: progress
 * data: {"step": "analysis"}
 *                                  ← 빈 줄이 오면 이벤트 하나가 끝난 것
 * : keep-alive                     ← ':'로 시작하면 주석(연결 유지용)
 * </pre>
 */
public final class RagSseReader {

    private RagSseReader() {
    }

    public interface Listener {

        void onEvent(String event, String data) throws IOException;

        void onComment(String comment) throws IOException;
    }

    public static void read(
            BufferedReader reader,
            Listener listener
    ) throws IOException {
        String event = null;
        StringBuilder data = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    listener.onEvent(
                            event != null ? event : "message",
                            data.toString()
                    );
                }

                event = null;
                data.setLength(0);
                continue;
            }

            if (line.startsWith(":")) {
                listener.onComment(line.substring(1).trim());
                continue;
            }

            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);

            if (value.startsWith(" ")) {
                value = value.substring(1);
            }

            switch (field) {
                case "event" -> event = value;
                case "data" -> {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                default -> {
                    // id, retry 등은 사용하지 않는다
                }
            }
        }

        // 마지막 이벤트 뒤에 빈 줄 없이 연결이 끝난 경우
        if (data.length() > 0) {
            listener.onEvent(
                    event != null ? event : "message",
                    data.toString()
            );
        }
    }
}