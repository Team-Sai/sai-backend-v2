package org.teamsai.saibackend.domain.contract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class RepaymentRedisGate {

    private static final DefaultRedisScript<Long> RELEASE =
            new DefaultRedisScript<>("""
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<Long> RENEW =
            new DefaultRedisScript<>("""
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('pexpire', KEYS[1], ARGV[2])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final ThreadPoolTaskScheduler scheduler;
    private final Duration leaseDuration;
    private final Duration renewInterval;
    private final Duration cooldown;

    public RepaymentRedisGate(
            StringRedisTemplate redis,
            @Qualifier("repaymentLockScheduler")
            ThreadPoolTaskScheduler scheduler,
            @Value("${sai.repayment.lock-lease:PT2M}")
            Duration leaseDuration,
            @Value("${sai.repayment.lock-renew-interval:PT20S}")
            Duration renewInterval,
            @Value("${sai.repayment.failure-cooldown:PT30S}")
            Duration cooldown
    ) {
        if (leaseDuration.toMillis() <= 0
                || renewInterval.toMillis() <= 0
                || cooldown.toMillis() <= 0
                || renewInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "Invalid repayment coordination durations");
        }

        this.redis = redis;
        this.scheduler = scheduler;
        this.leaseDuration = leaseDuration;
        this.renewInterval = renewInterval;
        this.cooldown = cooldown;
    }

    public boolean isCoolingDown(String failureScope) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(failureScope));
        } catch (RuntimeException exception) {
            log.warn(
                    "event=REPAYMENT_REDIS_FAILURE operation=cooldown_read exceptionType={}",
                    exception.getClass().getSimpleName()
            );

            throw new RepaymentGenerationDeferredException(
                    "REDIS_UNAVAILABLE");
        }
    }

    public int cooldownSeconds() {
        long milliseconds = cooldown.toMillis();

        return Math.toIntExact(
                milliseconds / 1000
                        + (milliseconds % 1000 == 0 ? 0 : 1)
        );
    }

    public void markFailure(String failureScope) {
        try {
            redis.opsForValue().set(failureScope, "1", cooldown);

            log.info(
                    "event=REPAYMENT_COOLDOWN_SET scope={} seconds={}",
                    failureScope,
                    cooldown.toSeconds()
            );
        } catch (RuntimeException exception) {
            // Redis에 기록하지 못해도 기존 로컬 cooldown은 적용된다.
            log.warn(
                    "event=REPAYMENT_REDIS_FAILURE operation=cooldown_write exceptionType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    public Optional<Lease> tryAcquire(String analysisKey) {
        String lockKey = analysisKey + ":lock";
        String token = UUID.randomUUID().toString();

        boolean acquired;

        try {
            acquired = Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(
                            lockKey,
                            token,
                            leaseDuration
                    )
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "event=REPAYMENT_REDIS_FAILURE operation=lock_acquire exceptionType={}",
                    exception.getClass().getSimpleName()
            );

            throw new RepaymentGenerationDeferredException(
                    "REDIS_UNAVAILABLE");
        }

        if (!acquired) {
            log.info(
                    "event=REPAYMENT_LOCK_BUSY key={}",
                    analysisKey
            );

            return Optional.empty();
        }

        Lease lease = new Lease(lockKey, token);

        try {
            lease.renewal = scheduler.scheduleAtFixedRate(
                    lease::renew,
                    renewInterval
            );
        } catch (RuntimeException exception) {
            lease.close();

            throw new RepaymentGenerationDeferredException(
                    "REDIS_UNAVAILABLE");
        }

        log.info(
                "event=REPAYMENT_LOCK_ACQUIRED key={}",
                analysisKey
        );

        return Optional.of(lease);
    }

    public class Lease implements AutoCloseable {

        private final String lockKey;
        private final String token;
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private ScheduledFuture<?> renewal;

        private Lease(String lockKey, String token) {
            this.lockKey = lockKey;
            this.token = token;
        }

        public String lockKey() {
            return lockKey;
        }

        public String token() {
            return token;
        }

        public boolean lost() {
            return lost.get();
        }

        private void renew() {
            if (closed.get() || lost.get()) {
                return;
            }

            try {
                Long result = redis.execute(
                        RENEW,
                        List.of(lockKey),
                        token,
                        Long.toString(leaseDuration.toMillis())
                );

                if (!Long.valueOf(1).equals(result)) {
                    lost.set(true);

                    log.warn(
                            "event=REPAYMENT_LOCK_LOST key={}",
                            lockKey
                    );
                }
            } catch (RuntimeException exception) {
                // 소유권을 확신할 수 없으면 결과 저장을 중단한다.
                lost.set(true);

                log.warn(
                        "event=REPAYMENT_REDIS_FAILURE operation=lock_renew exceptionType={}",
                        exception.getClass().getSimpleName()
                );
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            if (renewal != null) {
                renewal.cancel(false);
            }

            try {
                Long result = redis.execute(
                        RELEASE,
                        List.of(lockKey),
                        token
                );

                log.info(
                        "event=REPAYMENT_LOCK_RELEASED key={} owned={}",
                        lockKey,
                        Long.valueOf(1).equals(result)
                );
            } catch (RuntimeException exception) {
                // 해제 실패 시 TTL로 정리된다.
                log.warn(
                        "event=REPAYMENT_REDIS_FAILURE operation=lock_release exceptionType={}",
                        exception.getClass().getSimpleName()
                );
            }
        }
    }
}