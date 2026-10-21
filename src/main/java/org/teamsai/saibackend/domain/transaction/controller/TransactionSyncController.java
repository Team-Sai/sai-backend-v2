package org.teamsai.saibackend.domain.transaction.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingExecutionResult;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;
import org.teamsai.saibackend.domain.transaction.dto.response.TransactionSyncAllResponse;
import org.teamsai.saibackend.domain.transaction.exception.BankTransactionErrorCode;
import org.teamsai.saibackend.domain.transaction.service.TransactionSyncMatchService;
import org.teamsai.saibackend.global.security.CustomUserDetails;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Tag(name = "거래 동기화 API", description = "사이은행 거래내역 동기화 및 자동매칭 실행")
@RestController
@RequiredArgsConstructor
public class TransactionSyncController {

    private static final int SYNC_THREADS = 10;
    private static final int SYNC_QUEUE_CAPACITY = 50;
    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SYNC_ALL_TIMEOUT = Duration.ofSeconds(60);

    private final TransactionSyncMatchService transactionSyncMatchService;
    private final ExecutorService transactionSyncExecutor = new ThreadPoolExecutor(
            SYNC_THREADS,
            SYNC_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(SYNC_QUEUE_CAPACITY),
            new CustomizableThreadFactory("tx-sync-")
    );

    @Operation(summary = "연동계좌 거래 동기화")
    @PostMapping("/api/linked-accounts/{linkedAccountId}/sync")
    public ResponseEntity<AutoMatchingExecutionResult> sync(
            @PathVariable Long linkedAccountId,
            @RequestParam(required = false) MatchingTargetType targetType,
            @RequestParam(required = false) Long aggregateId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long userId = userDetails.getUserId();
        return ResponseEntity.ok(
                runOutsideRequestThread(() -> transactionSyncMatchService.syncAndMatch(
                        userId,
                        linkedAccountId,
                        targetType,
                        aggregateId
                ), SYNC_TIMEOUT)
        );
    }

    @Operation(summary = "전체 연동계좌 거래 동기화")
    @PostMapping("/api/transactions/sync")
    public ResponseEntity<TransactionSyncAllResponse> syncAll(
            @AuthenticationPrincipal
            CustomUserDetails userDetails
    ){
        Long userId = userDetails.getUserId();
        return ResponseEntity.ok(
                runOutsideRequestThread(() -> transactionSyncMatchService.syncAll(userId), SYNC_ALL_TIMEOUT)
        );
    }

    // 요청 스레드의 OSIV가 은행 응답을 기다리는 동안 DB 커넥션을 쥐고 있지 않도록 별도 스레드에서 실행
    private <T> T runOutsideRequestThread(Callable<T> task, Duration timeout) {
        Future<T> future;
        try {
            future = transactionSyncExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            throw BankTransactionErrorCode.TRANSACTION_SYNC_BUSY.toException();
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw BankTransactionErrorCode.TRANSACTION_SYNC_TIMEOUT.toException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw BankTransactionErrorCode.TRANSACTION_SYNC_BUSY.toException();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    @PreDestroy
    void shutdownExecutor() {
        transactionSyncExecutor.shutdown();
    }
}
