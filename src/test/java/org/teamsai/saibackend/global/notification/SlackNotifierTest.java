package org.teamsai.saibackend.global.notification;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SlackNotifierTest {
    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void reportsWhetherWebhookAcceptedMessage(int responseStatus) throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        try {
            var notifier = new SlackNotifier();
            ReflectionTestUtils.setField(notifier, "enabled", true);
            ReflectionTestUtils.setField(notifier, "webhookUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");

            assertThat(notifier.trySend("pending alert")).isEqualTo(responseStatus == 200);
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledOrUnconfiguredWebhookIsNotReportedAsDelivered() {
        var notifier = new SlackNotifier();
        assertThat(notifier.trySend("pending alert")).isFalse();
        ReflectionTestUtils.setField(notifier, "enabled", true);
        assertThat(notifier.trySend("pending alert")).isFalse();
    }
}
