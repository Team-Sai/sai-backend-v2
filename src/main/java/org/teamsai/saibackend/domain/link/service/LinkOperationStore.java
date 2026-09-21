package org.teamsai.saibackend.domain.link.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LinkOperationStore {
    public enum Status { PROCESSING, COMPLETED, FAILED, CONFIRM_UNKNOWN, COMPENSATION_PENDING }
    public record Operation(String id, Long userId, String requestHash,
                            String previousKey, String newKey, Status status) {}

    private final JdbcTemplate jdbc;

    public Optional<Operation> find(String id) {
        return jdbc.query("SELECT * FROM account_link_operation WHERE operation_id = ?",
                        (rs, row) -> new Operation(rs.getString("operation_id"), rs.getLong("user_id"),
                                rs.getString("request_hash"), rs.getString("previous_user_key"),
                                rs.getString("new_user_key"), Status.valueOf(rs.getString("status"))), id)
                .stream().findFirst();
    }

    public boolean hasUnresolved(Long userId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM account_link_operation
                WHERE user_id = ? AND status NOT IN ('COMPLETED', 'FAILED')
                """, Long.class, userId) > 0;
    }

    // confirm 여부가 불명확한 작업도 포함합니다.
    // 복구 정책은 AccountLinkCoordinator.recover()에서 적용합니다.
    public List<Operation> findUnresolved(Long userId) {
        return jdbc.query("""
                SELECT * FROM account_link_operation
                WHERE user_id = ? AND status NOT IN ('COMPLETED', 'FAILED')
                ORDER BY created_at, operation_id
                """, (rs, row) -> new Operation(rs.getString("operation_id"), rs.getLong("user_id"),
                rs.getString("request_hash"), rs.getString("previous_user_key"),
                rs.getString("new_user_key"), Status.valueOf(rs.getString("status"))), userId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void begin(Operation operation) {
        jdbc.update("""
                INSERT INTO account_link_operation
                    (operation_id, user_id, request_hash, previous_user_key, new_user_key, status)
                VALUES (?, ?, ?, ?, ?, 'PROCESSING')
                """, operation.id(), operation.userId(), operation.requestHash(),
                operation.previousKey(), operation.newKey());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mark(String id, Status status) {
        update(id, status);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String id) {
        update(id, Status.COMPLETED);
    }

    private void update(String id, Status status) {
        if (jdbc.update("""
                UPDATE account_link_operation SET status = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE operation_id = ?
                """, status.name(), id) != 1) {
            throw new IllegalStateException("계좌 연동 처리 기록을 찾을 수 없습니다.");
        }
    }
}
