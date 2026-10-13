package org.teamsai.saibackend.global.config;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;
import org.teamsai.saibackend.global.redis.ElastiCacheIamTokenGenerator;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import java.time.Clock;

/**
 * sai.redis.iam-auth.enabled=true 일 때만 켜진다. (AWS 운영 전용)
 * 꺼져 있으면 기존처럼 spring.data.redis.password 를 쓴다. (로컬·kind·테스트)
 * EKS에서는 Pod Identity(sai/sai-api) 자격 증명으로 토큰을 서명한다. 액세스 키를 설정에 넣지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "sai.redis.iam-auth.enabled", havingValue = "true")
public class RedisIamAuthConfig {

    @Bean
    public ElastiCacheIamTokenGenerator elastiCacheIamTokenGenerator(
            @Value("${sai.redis.iam-auth.replication-group-id}") String replicationGroupId,
            @Value("${sai.redis.iam-auth.user-id}") String userId,
            @Value("${sai.redis.iam-auth.region:ap-northeast-2}") String region) {
        return new ElastiCacheIamTokenGenerator(
                replicationGroupId, userId, region,
                DefaultCredentialsProvider.builder().build(),
                Clock.systemUTC());
    }

    /** Lettuce가 새로 접속(재접속 포함)할 때마다 사용자 ID + 최신 토큰으로 인증한다. */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisIamCredentialsCustomizer(
            ElastiCacheIamTokenGenerator tokenGenerator) {
        RedisCredentialsProviderFactory factory = new RedisCredentialsProviderFactory() {
            @Override
            public RedisCredentialsProvider createCredentialsProvider(RedisConfiguration configuration) {
                return RedisCredentialsProvider.from(
                        () -> RedisCredentials.just(tokenGenerator.userId(), tokenGenerator.token()));
            }
        };
        return builder -> builder.redisCredentialsProviderFactory(factory);
    }
}