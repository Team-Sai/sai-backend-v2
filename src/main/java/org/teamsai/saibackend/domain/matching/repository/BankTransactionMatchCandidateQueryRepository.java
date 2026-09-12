package org.teamsai.saibackend.domain.matching.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionMatchCandidateQueryDTO;
import org.teamsai.saibackend.domain.matching.type.MatchingAmountType;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class BankTransactionMatchCandidateQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String CURRENT_REMAINING_AMOUNT = """
            CASE
                WHEN mc.target_type = 'SETTLEMENT'
                    THEN GREATEST(
                        obligation.expected_amount - COALESCE((
                            SELECT SUM(pr.amount)
                            FROM payment_record pr
                            WHERE pr.payment_target_type = 'SETTLEMENT'
                            AND pr.target_id = obligation.payment_obligation_id
                            AND pr.record_status = 'CONFIRMED'
                        ), 0),
                        0
                    )
                WHEN mc.target_type = 'LOAN'
                    THEN GREATEST(
                        schedule.total_payment_due - COALESCE((
                            SELECT SUM(pr.amount)
                            FROM payment_record pr
                            WHERE pr.payment_target_type = 'LOAN'
                            AND pr.target_id = schedule.schedule_id
                            AND pr.record_status = 'CONFIRMED'
                        ), 0),
                        0
                    )
            END
            """;

    private static final String REVIEW_SQL = """
            SELECT
                mc.match_candidate_id,
                mc.bank_transaction_id,
                mc.target_type,
                mc.target_id,
                CASE
                    WHEN mc.target_type = 'SETTLEMENT'
                        THEN participant.settlement_id
                    WHEN mc.target_type = 'LOAN'
                        THEN schedule.contract_id
                END AS aggregate_id,
                COALESCE(settlement.title, contract.contract_alias)
                    AS target_name,
                COALESCE(settlement_user.name, debtor.name)
                    AS participant_name,
                mc.expected_remaining_amount,
                mc.amount_match_type,
                mc.created_at
            FROM bank_transaction_match_candidate mc
            JOIN bank_transaction bt
                ON bt.bank_transaction_id = mc.bank_transaction_id
            LEFT JOIN payment_obligation obligation
                ON mc.target_type = 'SETTLEMENT'
                AND obligation.payment_obligation_id = mc.target_id
            LEFT JOIN settlement_participant participant
                ON participant.participant_id = obligation.participant_id
            LEFT JOIN settlement settlement
                ON settlement.settlement_id = participant.settlement_id
            LEFT JOIN users settlement_user
                ON settlement_user.user_id = participant.user_id
            LEFT JOIN repayment_schedule schedule
                ON mc.target_type = 'LOAN'
                AND schedule.schedule_id = mc.target_id
            LEFT JOIN loan_contract contract
                ON contract.contract_id = schedule.contract_id
            LEFT JOIN users debtor
                ON debtor.user_id = contract.debtor_id
            WHERE mc.bank_transaction_id IN
            (:bankTransactionIds)
            AND mc.candidate_status = 'AVAILABLE'
            AND (
                (
                    mc.target_type = 'SETTLEMENT'
                    AND obligation.obligation_status = 'ACTIVE'
                    AND participant.participant_status = 'ACTIVE'
                    AND settlement.settlement_status = 'IN_PROGRESS'
                    AND obligation.expected_amount > COALESCE((
                        SELECT SUM(pr.amount)
                        FROM payment_record pr
                        WHERE pr.payment_target_type = 'SETTLEMENT'
                        AND pr.target_id = obligation.payment_obligation_id
                        AND pr.record_status = 'CONFIRMED'
                    ), 0)
                )
                OR (
                    mc.target_type = 'LOAN'
                    AND schedule.status = 'PENDING'
                    AND schedule.total_payment_due > COALESCE((
                        SELECT SUM(pr.amount)
                        FROM payment_record pr
                        WHERE pr.payment_target_type = 'LOAN'
                        AND pr.target_id = schedule.schedule_id
                        AND pr.record_status = 'CONFIRMED'
                    ), 0)
                )
            )
            AND bt.amount >= (
                %1$s
            ) * 0.10
            AND bt.amount <= (
                %1$s
            ) * 1.10
            """.formatted(CURRENT_REMAINING_AMOUNT);

    public List<BankTransactionMatchCandidateQueryDTO> findAllForReviewByBankTransactionId(
            Long bankTransactionId
    ) {
        return findAllForReviewByBankTransactionIds(List.of(bankTransactionId), null, null);
    }

    @SuppressWarnings("unchecked")
    public List<BankTransactionMatchCandidateQueryDTO> findAllForReviewByBankTransactionIds(
            List<Long> bankTransactionIds,
            MatchingTargetType targetType,
            Long aggregateId
    ) {
        if (bankTransactionIds.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder(REVIEW_SQL);
        if (targetType != null) {
            sql.append("\nAND mc.target_type = :targetType");
        }
        if (aggregateId != null) {
            sql.append("\n").append("""
                    AND CASE
                        WHEN mc.target_type = 'SETTLEMENT'
                            THEN participant.settlement_id
                        WHEN mc.target_type = 'LOAN'
                            THEN schedule.contract_id
                        END = :aggregateId
                    """);
        }
        sql.append("\nORDER BY mc.bank_transaction_id ASC, mc.match_candidate_id ASC");

        Query query = entityManager.createNativeQuery(
                sql.toString(),
                Tuple.class
        );
        query.setParameter("bankTransactionIds", bankTransactionIds);
        if (targetType != null) {
            query.setParameter("targetType", targetType.name());
        }
        if (aggregateId != null) {
            query.setParameter("aggregateId", aggregateId);
        }

        List<Tuple> rows = query.getResultList();
        return rows.stream().map(this::toDto).toList();
    }

    private BankTransactionMatchCandidateQueryDTO toDto(Tuple row) {
        Number aggregateId = row.get("aggregate_id", Number.class);
        return BankTransactionMatchCandidateQueryDTO.builder()
                .matchCandidateId(row.get("match_candidate_id", Number.class).longValue())
                .bankTransactionId(row.get("bank_transaction_id", Number.class).longValue())
                .targetType(MatchingTargetType.valueOf(row.get("target_type", String.class)))
                .targetId(row.get("target_id", Number.class).longValue())
                .aggregateId(aggregateId == null ? null : aggregateId.longValue())
                .targetName(row.get("target_name", String.class))
                .participantName(row.get("participant_name", String.class))
                .expectedRemainingAmount(row.get("expected_remaining_amount", BigDecimal.class))
                .amountMatchType(MatchingAmountType.valueOf(row.get("amount_match_type", String.class)))
                .createdAt(toLocalDateTime(row.get("created_at")))
                .build();
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        // Native DATETIME 반환 타입은 Hibernate/JDBC 설정에 따라 다를 수 있다.
        return value instanceof LocalDateTime dateTime
                ? dateTime : ((Timestamp) value).toLocalDateTime();
    }
}
