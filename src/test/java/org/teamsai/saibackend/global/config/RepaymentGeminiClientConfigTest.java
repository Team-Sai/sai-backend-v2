package org.teamsai.saibackend.global.config;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class RepaymentGeminiClientConfigTest {

    @Test
    @Timeout(10)
    void stopsWaitingForSlowHttpResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch releaseResponse = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.setExecutor(executor);

        server.createContext("/", exchange -> {
            calls.incrementAndGet();

            try {
                exchange.getRequestBody().readAllBytes();

                // 클라이언트 timeout보다 오래 응답을 보류한다.
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        server.start();

        try {
            HttpOptions options =
                    RepaymentGeminiClientConfig.requestOptions(
                                    Duration.ofMillis(500)
                            ).toBuilder()
                            .baseUrl(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                            )
                            .build();

            try (Client client = Client.builder()
                    .apiKey("test-key")
                    .httpOptions(options)
                    .build()) {

                long started = System.nanoTime();

                assertThatThrownBy(() ->
                        client.models.generateContent(
                                "timeout-test",
                                "hello",
                                GenerateContentConfig.builder().build()
                        )
                ).isInstanceOf(RuntimeException.class);

                long elapsedMillis =
                        TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - started);

                assertThat(elapsedMillis).isLessThan(4000);
                assertThat(calls.get()).isEqualTo(1);
            }
        } finally {
            releaseResponse.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void doesNotRetryHttp503() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();

            byte[] body = """
                    {
                      "error": {
                        "code": 503,
                        "message": "temporary failure",
                        "status": "UNAVAILABLE"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
            );

            exchange.sendResponseHeaders(503, body.length);

            try (var output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });

        server.start();

        try {
            HttpOptions options =
                    RepaymentGeminiClientConfig.requestOptions(
                                    Duration.ofSeconds(2)
                            ).toBuilder()
                            .baseUrl(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                            )
                            .build();

            try (Client client = Client.builder()
                    .apiKey("test-key")
                    .httpOptions(options)
                    .build()) {

                assertThatThrownBy(() ->
                        client.models.generateContent(
                                "retry-test",
                                "hello",
                                GenerateContentConfig.builder().build()
                        )
                ).isInstanceOf(RuntimeException.class);

                assertThat(calls.get()).isEqualTo(1);
            }
        } finally {
            server.stop(0);
        }
    }
}