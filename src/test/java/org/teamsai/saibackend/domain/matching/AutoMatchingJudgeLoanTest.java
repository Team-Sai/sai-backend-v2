package org.teamsai.saibackend.domain.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingResult;
import org.teamsai.saibackend.domain.matching.model.MatchingCandidate;
import org.teamsai.saibackend.domain.matching.model.MatchingTransaction;
import org.teamsai.saibackend.domain.matching.type.AutoMatchingTransactionType;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMatchingJudgeLoanTest {

    private final AutoMatchingJudge judge = new AutoMatchingJudge();

    @Test
    @DisplayName("차용증은 회차 금액을 넘으면 미매칭이다")
    void loanOverAmountIsUnmatched() {
        assertThat(judgeAmount(MatchingTargetType.LOAN).isUnmatched()).isTrue();
    }

    @Test
    @DisplayName("정산은 110% 이내면 후보로 남는다")
    void settlementKeepsExistingRange() {
        assertThat(judgeAmount(MatchingTargetType.SETTLEMENT).isUnmatched()).isFalse();
    }

    private AutoMatchingResult judgeAmount(MatchingTargetType type) {
        MatchingTransaction transaction = new MatchingTransaction(
                1L, AutoMatchingTransactionType.DEPOSIT, new BigDecimal("105000"), "홍길동", LocalDateTime.now());
        MatchingCandidate candidate = new MatchingCandidate(
                type, 2L, 3L, "홍길동", new BigDecimal("100000"));

        return judge.judge(transaction, List.of(candidate));
    }
}