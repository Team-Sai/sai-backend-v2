package org.teamsai.saibackend.global.redis;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * ElastiCache Redis IAM 인증 토큰을 만든다.
 * 토큰 = "elasticache" 서비스용 SigV4 서명 URL (http:// 제외). 15분 유효.
 * 매 접속마다 서명하지 않도록 10분 동안 재사용한다.
 */
public class ElastiCacheIamTokenGenerator {

    private static final Duration TOKEN_LIFETIME = Duration.ofSeconds(900);   // AWS 최대 15분
    private static final Duration REFRESH_AFTER = Duration.ofMinutes(10);      // 만료 전에 미리 갱신

    private final String replicationGroupId;
    private final String userId;
    private final String region;
    private final AwsCredentialsProvider credentialsProvider;
    private final Clock clock;
    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();

    private String cachedToken;
    private Instant cachedAt = Instant.EPOCH;

    public ElastiCacheIamTokenGenerator(String replicationGroupId, String userId, String region,
                                        AwsCredentialsProvider credentialsProvider, Clock clock) {
        this.replicationGroupId = replicationGroupId;
        this.userId = userId;
        this.region = region;
        this.credentialsProvider = credentialsProvider;
        this.clock = clock;
    }

    public String userId() {
        return userId;
    }

    public synchronized String token() {
        Instant now = clock.instant();
        if (cachedToken == null || now.isAfter(cachedAt.plus(REFRESH_AFTER))) {
            cachedToken = createToken();
            cachedAt = now;
        }
        return cachedToken;
    }

    private String createToken() {
        SdkHttpRequest request = SdkHttpRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("http")
                .host(replicationGroupId)
                .encodedPath("/")
                .putRawQueryParameter("Action", "connect")
                .putRawQueryParameter("User", userId)
                .build();

        SignedRequest signed = signer.sign(r -> r
                .identity(credentialsProvider.resolveCredentials())
                .request(request)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "elasticache")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4HttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, TOKEN_LIFETIME));

        return signed.request().getUri().toString().replaceFirst("^http://", "");
    }
}