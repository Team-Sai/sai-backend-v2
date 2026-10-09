package org.teamsai.saibackend.global.redis;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ElastiCacheIamTokenGeneratorTest {

    // 테스트용 가짜 키 (AWS 문서 예시 값, 실제 계정 아님)
    private final StaticCredentialsProvider fakeCredentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"));

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void 토큰은_그룹ID로_시작하고_connect_서명_정보를_포함한다() {
        var generator = new ElastiCacheIamTokenGenerator(
                "sai-dev-redis", "sai-dev-application", "ap-northeast-2", fakeCredentials, fixedClock);

        String token = generator.token();

        assertThat(token).startsWith("sai-dev-redis/");
        assertThat(token).doesNotStartWith("http");
        assertThat(token).contains("Action=connect", "User=sai-dev-application",
                "X-Amz-Algorithm=AWS4-HMAC-SHA256", "X-Amz-Expires=900", "X-Amz-Signature=");
        assertThat(token).contains("ap-northeast-2%2Felasticache%2Faws4_request");
    }

    @Test
    void 십분_안에는_같은_토큰을_재사용한다() {
        var generator = new ElastiCacheIamTokenGenerator(
                "sai-dev-redis", "sai-dev-application", "ap-northeast-2", fakeCredentials, fixedClock);

        assertThat(generator.token()).isSameAs(generator.token());
    }
}