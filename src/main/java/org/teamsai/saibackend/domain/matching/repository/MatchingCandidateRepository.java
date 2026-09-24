package org.teamsai.saibackend.domain.matching.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Repository;
import org.teamsai.saibackend.domain.matching.model.MatchingCandidate;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class MatchingCandidateRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String FIND_CANDIDATES_SQL = """
            SELECT
                'SETTLEMENT' AS target_type,
                po.payment_obligation_id AS target_id,
                po.participant_id AS participant_id,
                u.name AS participant_name,
                po.expected_amount - COALESCE(SUM(pr.amount), 0)
                    AS remaining_amount
            FROM payment_obligation po
            JOIN settlement_participant sp
                ON sp.participant_id = po.participant_id
            JOIN settlement s
                ON s.settlement_id = sp.settlement_id
            JOIN settlement_account sa
                ON sa.settlement_id = s.settlement_id
            JOIN users u
                ON u.user_id = sp.user_id
            LEFT JOIN payment_record pr
                ON pr.payment_target_type = 'SETTLEMENT'
                AND pr.target_id = po.payment_obligation_id
                AND pr.record_status = 'CONFIRMED'
            WHERE sa.linked_account_id = :linkedAccountId
                AND sa.selected_at <= :transactionAt
                AND (
                    sa.ended_at IS NULL
                    OR sa.ended_at > :transactionAt
                )
                AND s.created_at <= :transactionAt
                AND s.settlement_status = 'IN_PROGRESS'
                AND po.obligation_status = 'ACTIVE'
                AND po.payment_status <> 'PAID'
                AND sp.participant_status = 'ACTIVE'
                %s
            GROUP BY
                po.payment_obligation_id,
                po.participant_id,
                u.name,
                po.expected_amount
            HAVING
                po.expected_amount - COALESCE(SUM(pr.amount), 0) > 0

            UNION ALL

            SELECT
                'LOAN' AS target_type,
                rs.schedule_id AS target_id,
                lc.debtor_id AS participant_id,
                debtor.name AS participant_name,
                rs.total_payment_due - COALESCE(SUM(pr.amount), 0)
                    AS remaining_amount
            FROM repayment_schedule rs
            JOIN loan_contract lc
                ON lc.contract_id = rs.contract_id
            JOIN contract_account ca
                ON ca.contract_id = lc.contract_id
            JOIN users debtor
                ON debtor.user_id = lc.debtor_id
            LEFT JOIN payment_record pr
                ON pr.payment_target_type = 'LOAN'
                AND pr.target_id = rs.schedule_id
                AND pr.record_status = 'CONFIRMED'
            WHERE ca.linked_account_id = :linkedAccountId
                AND ca.selected_at <= :transactionAt
                AND (
                    ca.ended_at IS NULL
                    OR ca.ended_at > :transactionAt
                )
                AND ca.account_status = 'ACTIVE'
                AND lc.status = 'COMPLETED'
                AND lc.created_at <= :transactionAt
                AND rs.created_at <= :transactionAt
                AND rs.status = 'PENDING'
                %s
                AND NOT EXISTS (
                    SELECT 1
                    FROM repayment_schedule earlier
                    WHERE earlier.contract_id = rs.contract_id
                        AND earlier.status = 'PENDING'
                        AND earlier.sequence < rs.sequence
                )
            GROUP BY
                rs.schedule_id,
                lc.debtor_id,
                debtor.name,
                rs.total_payment_due
            HAVING
                rs.total_payment_due - COALESCE(SUM(pr.amount), 0) > 0
            """;


    public List<MatchingCandidate> findMatchCandidatesByLinkedAccountId(
            Long linkedAccountId,
            LocalDateTime transactionAt
    ) {
        return findMatchCandidatesByLinkedAccountIdAndTarget(
                linkedAccountId,
                transactionAt,
                null,
                null
        );
    }
    @SuppressWarnings("unchecked")
    public List<MatchingCandidate> findMatchCandidatesByLinkedAccountIdAndTarget(
            Long linkedAccountId,
            LocalDateTime transactionAt,
            MatchingTargetType targetType,
            Long aggregateId
    ) {

        boolean scoped = targetType != null && aggregateId != null;

        String settlementCondition = scoped
                ? """
                    AND :targetType = 'SETTLEMENT'
                    AND s.settlement_id = :aggregateId
                    """
                : "";

        String loanCondition = scoped
                ? """
                    AND :targetType = 'LOAN'
                    AND lc.contract_id = :aggregateId
                    """
                : "";

        String sql = FIND_CANDIDATES_SQL.formatted(
                settlementCondition,
                loanCondition
        );

        Query query = entityManager.createNativeQuery(sql, Tuple.class);

        query.setParameter("linkedAccountId", linkedAccountId);

        query.setParameter("transactionAt", transactionAt);

        if (scoped) {

            query.setParameter("targetType", targetType.name());

            query.setParameter("aggregateId", aggregateId);
        }

        List<Tuple> rows = query.getResultList();
        return rows.stream().map(this::toMatchingCandidate).toList();
    }

    private MatchingCandidate toMatchingCandidate(Tuple row) {
        // UNION ALL 결과도 SELECT 별칭으로 읽는다.
        return new MatchingCandidate(
                MatchingTargetType.valueOf(row.get("target_type", String.class)),
                row.get("target_id", Number.class).longValue(),
                row.get("participant_id", Number.class).longValue(),
                row.get("participant_name", String.class),
                row.get("remaining_amount", BigDecimal.class)
        );
    }
}
