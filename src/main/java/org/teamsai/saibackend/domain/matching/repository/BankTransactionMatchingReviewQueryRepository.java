package org.teamsai.saibackend.domain.matching.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import org.teamsai.saibackend.domain.matching.dto.request.MatchingReviewSearchCondition;
import org.teamsai.saibackend.domain.transaction.dto.BankTransactionDTO;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class BankTransactionMatchingReviewQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String CANDIDATE_VALIDITY = """
            AND (
                (
                    available_candidate.target_type = 'SETTLEMENT'
                    AND EXISTS (
                        SELECT 1
                        FROM payment_obligation review_obligation
                        JOIN settlement_participant review_participant
                            ON review_participant.participant_id = review_obligation.participant_id
                        JOIN settlement review_settlement
                            ON review_settlement.settlement_id = review_participant.settlement_id
                        WHERE review_obligation.payment_obligation_id = available_candidate.target_id
                        AND review_obligation.obligation_status = 'ACTIVE'
                        AND review_participant.participant_status = 'ACTIVE'
                        AND review_settlement.settlement_status = 'IN_PROGRESS'
                        AND review_obligation.expected_amount > COALESCE((
                            SELECT SUM(pr.amount)
                            FROM payment_record pr
                            WHERE pr.payment_target_type = 'SETTLEMENT'
                            AND pr.target_id = review_obligation.payment_obligation_id
                            AND pr.record_status = 'CONFIRMED'
                        ), 0)
                        AND bt.amount >= (
                            review_obligation.expected_amount - COALESCE((
                                SELECT SUM(pr.amount)
                                FROM payment_record pr
                                WHERE pr.payment_target_type = 'SETTLEMENT'
                                AND pr.target_id = review_obligation.payment_obligation_id
                                AND pr.record_status = 'CONFIRMED'
                            ), 0)
                        ) * 0.10
                        AND bt.amount <= (
                            review_obligation.expected_amount - COALESCE((
                                SELECT SUM(pr.amount)
                                FROM payment_record pr
                                WHERE pr.payment_target_type = 'SETTLEMENT'
                                AND pr.target_id = review_obligation.payment_obligation_id
                                AND pr.record_status = 'CONFIRMED'
                            ), 0)
                        ) * 1.10
                    )
                )
                OR (
                    available_candidate.target_type = 'LOAN'
                    AND EXISTS (
                        SELECT 1
                        FROM repayment_schedule review_schedule
                        WHERE review_schedule.schedule_id = available_candidate.target_id
                        AND review_schedule.status = 'PENDING'
                        AND review_schedule.total_payment_due > COALESCE((
                            SELECT SUM(pr.amount)
                            FROM payment_record pr
                            WHERE pr.payment_target_type = 'LOAN'
                            AND pr.target_id = review_schedule.schedule_id
                            AND pr.record_status = 'CONFIRMED'
                        ), 0)
                        AND bt.amount >= (
                            review_schedule.total_payment_due - COALESCE((
                                SELECT SUM(pr.amount)
                                FROM payment_record pr
                                WHERE pr.payment_target_type = 'LOAN'
                                AND pr.target_id = review_schedule.schedule_id
                                AND pr.record_status = 'CONFIRMED'
                            ), 0)
                        ) * 0.10
                        AND bt.amount <= (
                            review_schedule.total_payment_due - COALESCE((
                                SELECT SUM(pr.amount)
                                FROM payment_record pr
                                WHERE pr.payment_target_type = 'LOAN'
                                AND pr.target_id = review_schedule.schedule_id
                                AND pr.record_status = 'CONFIRMED'
                            ), 0)
                        ) * 1.10
                    )
                )
            )
            """;

    private static final String REVIEW_SEARCH = """
            FROM bank_transaction bt
            INNER JOIN linked_bank_account account
                ON account.linked_account_id = bt.linked_account_id
            WHERE account.user_id = :userId
            AND account.connection_status = 'AVAILABLE'
            AND bt.processing_status = 'NEEDS_CHECK'
            AND bt.transaction_type = 'DEPOSIT'
            AND EXISTS (
                SELECT 1
                FROM bank_transaction_match_candidate available_candidate
                WHERE available_candidate.bank_transaction_id = bt.bank_transaction_id
                AND available_candidate.candidate_status = 'AVAILABLE'
                %s
            )
            """.formatted(CANDIDATE_VALIDITY);

    private static final String TARGET_FILTER = """
            AND EXISTS (
                SELECT 1
                FROM bank_transaction_match_candidate available_candidate
                WHERE available_candidate.bank_transaction_id = bt.bank_transaction_id
                AND available_candidate.candidate_status = 'AVAILABLE'
                AND available_candidate.target_type = :targetType
                %s
            )
            """.formatted(CANDIDATE_VALIDITY);

    private static final String AGGREGATE_FILTER = """
            AND EXISTS (
                SELECT 1
                FROM bank_transaction_match_candidate available_candidate
                LEFT JOIN payment_obligation obligation
                    ON available_candidate.target_type = 'SETTLEMENT'
                    AND obligation.payment_obligation_id = available_candidate.target_id
                LEFT JOIN settlement_participant participant
                    ON participant.participant_id = obligation.participant_id
                LEFT JOIN repayment_schedule schedule
                    ON available_candidate.target_type = 'LOAN'
                    AND schedule.schedule_id = available_candidate.target_id
                WHERE available_candidate.bank_transaction_id = bt.bank_transaction_id
                AND available_candidate.candidate_status = 'AVAILABLE'
                AND available_candidate.target_type = :targetType
                %s
                AND CASE
                    WHEN available_candidate.target_type = 'SETTLEMENT'
                        THEN participant.settlement_id
                    WHEN available_candidate.target_type = 'LOAN'
                        THEN schedule.contract_id
                    END = :aggregateId
            )
            """.formatted(CANDIDATE_VALIDITY);

    @SuppressWarnings("unchecked")
    public List<BankTransactionDTO> search(Long userId, MatchingReviewSearchCondition condition) {
        String sql = """
                SELECT bt.bank_transaction_id, bt.linked_account_id,
                       bt.external_transaction_id, bt.amount,
                       bt.transaction_type, bt.processing_status,
                       bt.transaction_at, bt.counterparty_name, bt.memo, bt.synced_at
                """ + searchCondition(condition) + """
                ORDER BY bt.transaction_at DESC, bt.bank_transaction_id DESC
                LIMIT :size OFFSET :offset
                """;
        Query query = bindParameters(
                entityManager.createNativeQuery(sql, Tuple.class), userId, condition
        );
        // 기존 long 기반 LIMIT/OFFSET 바인딩을 유지한다.
        query.setParameter("size", condition.size());
        query.setParameter("offset", condition.offset());
        List<Tuple> rows = query.getResultList();
        return rows.stream().map(this::toDto).toList();
    }

    public long count(Long userId, MatchingReviewSearchCondition condition) {
        Query query = bindParameters(
                entityManager.createNativeQuery("SELECT COUNT(*)\n" + searchCondition(condition)),
                userId,
                condition
        );
        return ((Number) query.getSingleResult()).longValue();
    }

    private String searchCondition(MatchingReviewSearchCondition condition) {
        StringBuilder sql = new StringBuilder(REVIEW_SEARCH);
        if (condition.targetType() != null) {
            sql.append(TARGET_FILTER);
        }
        if (condition.aggregateId() != null) {
            sql.append(AGGREGATE_FILTER);
        }
        return sql.toString();
    }

    private Query bindParameters(Query query, Long userId, MatchingReviewSearchCondition condition) {
        query.setParameter("userId", userId);
        if (condition.targetType() != null) {
            query.setParameter("targetType", condition.targetType().name());
        }
        if (condition.aggregateId() != null) {
            query.setParameter("aggregateId", condition.aggregateId());
        }
        return query;
    }

    private BankTransactionDTO toDto(Tuple row) {
        return BankTransactionDTO.builder()
                .bankTransactionId(row.get("bank_transaction_id", Number.class).longValue())
                .linkedAccountId(row.get("linked_account_id", Number.class).longValue())
                .externalTransactionId(row.get("external_transaction_id", String.class))
                .amount(row.get("amount", BigDecimal.class))
                .transactionType(BankTransactionType.valueOf(row.get("transaction_type", String.class)))
                .processingStatus(BankTransactionProcessingStatus.valueOf(row.get("processing_status", String.class)))
                .transactionAt(toLocalDateTime(row.get("transaction_at")))
                .counterpartyName(row.get("counterparty_name", String.class))
                .memo(row.get("memo", String.class))
                .syncedAt(toLocalDateTime(row.get("synced_at")))
                .build();
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof LocalDateTime dateTime
                ? dateTime : ((Timestamp) value).toLocalDateTime();
    }
}
