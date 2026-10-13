package org.teamsai.saibackend.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.global.util.LinkIdentityHasher;

import javax.crypto.SecretKey;
import java.time.LocalDate;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtTokenProvider {

    private static final String CLAIM_PURPOSE = "purpose";
    private static final String CLAIM_IDENTITY_HASH = "identity-hash";

    private static final String PURPOSE_ACCESS = "access";
    private static final String PURPOSE_REFRESH = "refresh";
    private static final String PURPOSE_BANK_LINK = "bank-link";

    private final SecretKey accessSigningKey;
    private final SecretKey linkStateSigningKey;

    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;
    private final long linkStateExpirationMs;

    private final String linkIdentityHashSecret;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String accessSecret,
            @Value("${link-state.secret}") String linkStateSecret,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
            @Value("${jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs,
            @Value("${jwt.link-state-expiration-ms}") long linkStateExpirationMs,
            @Value("${link-identity.hash-secret}") String linkIdentityHashSecret
    ) {
        this.accessSigningKey =
                Keys.hmacShaKeyFor(
                        Decoders.BASE64.decode(accessSecret)
                );

        this.linkStateSigningKey =
                Keys.hmacShaKeyFor(
                        Decoders.BASE64.decode(linkStateSecret)
                );

        this.accessTokenExpirationMs =
                accessTokenExpirationMs;

        this.refreshTokenExpirationMs =
                refreshTokenExpirationMs;

        this.linkStateExpirationMs =
                linkStateExpirationMs;

        this.linkIdentityHashSecret =
                linkIdentityHashSecret;
    }

    public String createAccessToken(Long userId) {
        Date issuedAt = new Date();

        Date expiration =
                new Date(
                        issuedAt.getTime()
                                + accessTokenExpirationMs
                );

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(
                        CLAIM_PURPOSE,
                        PURPOSE_ACCESS
                )
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(accessSigningKey)
                .compact();
    }

    public String createRefreshToken(Long userId) {
        Date issuedAt = new Date();

        Date expiration =
                new Date(
                        issuedAt.getTime()
                                + refreshTokenExpirationMs
                );

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(
                        CLAIM_PURPOSE,
                        PURPOSE_REFRESH
                )
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(accessSigningKey)
                .compact();
    }

    /**
     * 계좌 연동용 state JWT를 생성한다.
     *
     * identity-hash는 mock-bank의 validateExpectedIdentity()에서
     * 서비스 사용자와 은행 로그인 사용자의 명의 일치 여부를 검증하는 데 사용한다.
     * 양쪽 서버는 동일한 해시 생성 규칙과 link-identity.hash-secret을 사용해야 한다.
     */
    public String createLinkStateToken(
            Long userId,
            String name,
            LocalDate birthDate
    ) {
        Date issuedAt = new Date();

        Date expiration =
                new Date(
                        issuedAt.getTime()
                                + linkStateExpirationMs
                );

        String identityHash =
                LinkIdentityHasher.hash(
                        name,
                        birthDate,
                        linkIdentityHashSecret
                );
        // 연동 시작마다 서로 다른 state를 생성한다.
        // Coordinator는 state 전체의 해시를 작업 ID로 사용하여 중복 콜백을 구분한다.
        return Jwts.builder()
                .id(java.util.UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim(
                        CLAIM_PURPOSE,
                        PURPOSE_BANK_LINK
                )
                .claim(
                        CLAIM_IDENTITY_HASH,
                        identityHash
                )
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(linkStateSigningKey)
                .compact();
    }

    public Optional<Long> getUserIdFromLinkState(
            String token
    ) {
        return getUserIdIfPurposeMatches(
                token,
                PURPOSE_BANK_LINK,
                linkStateSigningKey
        );
    }

    public Optional<Long> getUserIdIfValid(
            String token
    ) {
        return getUserIdIfPurposeMatches(
                token,
                PURPOSE_ACCESS,
                accessSigningKey
        );
    }

    public Optional<Long> getUserIdFromRefreshToken(
            String token
    ) {
        return getUserIdIfPurposeMatches(
                token,
                PURPOSE_REFRESH,
                accessSigningKey
        );
    }

    private Optional<Long> getUserIdIfPurposeMatches(
            String token,
            String expectedPurpose,
            SecretKey key
    ) {
        try {
            Claims claims =
                    parseClaims(
                            token,
                            key
                    );

            if (!expectedPurpose.equals(
                    claims.get(
                            CLAIM_PURPOSE,
                            String.class
                    )
            )) {
                return Optional.empty();
            }

            String subject =
                    claims.getSubject();

            if (
                    subject == null
                            || subject.isBlank()
            ) {
                return Optional.empty();
            }

            return Optional.of(
                    Long.valueOf(subject)
            );

        } catch (
                JwtException
                | IllegalArgumentException exception
        ) {
            return Optional.empty();
        }
    }

    private Claims parseClaims(
            String token,
            SecretKey key
    ) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}