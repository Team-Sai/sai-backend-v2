package org.teamsai.saibackend.domain.link.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Component
public class UserLinkLock {
    private final DataSource dataSource;
    private final Semaphore slots;

    public UserLinkLock(
            DataSource dataSource,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}")
            int poolSize,
            @Value("${account-link.max-concurrent:1}")
            int maxConcurrent
    ) {
        if (poolSize < 2) {
            throw new IllegalArgumentException(
                    "계좌 연동에는 최소 2개의 DB 연결이 필요합니다."
            );
        }

        // 연동 작업 하나가 잠금용 커넥션과 업무용 커넥션을 사용한다.
        if (maxConcurrent < 1 || maxConcurrent > poolSize / 2) {
            throw new IllegalArgumentException(
                    "계좌 연동 동시 실행 수는 1 이상, DB 풀 크기의 절반 이하여야 합니다."
            );
        }

        this.dataSource = dataSource;
        this.slots = new Semaphore(maxConcurrent);
    }

    public <T> T execute(Long userId, Supplier<T> action) {
        if (!slots.tryAcquire()) {
            throw AccountErrorCode.LINK_IN_PROGRESS.toException();
        }
        try {
            return executeWithConnection(userId, action);
        } finally {
            slots.release();
        }
    }

    private <T> T executeWithConnection(Long userId, Supplier<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            String name = "sai:link:" + connection.getCatalog() + ":" + userId;
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, name);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw AccountErrorCode.LINK_IN_PROGRESS.toException();
                    }
                }
            }
            try {
                return action.get();
            } finally {
                try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    statement.setString(1, name);
                    statement.execute();
                } catch (SQLException failure) {
                    connection.abort(Runnable::run);
                    throw failure;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("계좌 연동 잠금 처리에 실패했습니다.", e);
        }
    }
}
