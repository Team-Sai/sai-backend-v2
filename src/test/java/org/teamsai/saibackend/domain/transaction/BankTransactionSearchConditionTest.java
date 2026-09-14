package org.teamsai.saibackend.domain.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.transaction.dto.request.BankTransactionSearchCondition;
import org.teamsai.saibackend.domain.transaction.exception.BankTransactionErrorCode;
import org.teamsai.saibackend.global.exception.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankTransactionSearchConditionTest {

    @Test
    @DisplayName("int 범위를 넘는 페이지 번호를 거부한다")
    void rejectsPageBeyondIntegerRange() {
        assertInvalidPagination(2_147_483_648L, 1);
    }

    @Test
    @DisplayName("페이지 번호가 int 범위여도 offset이 범위를 넘으면 거부한다")
    void rejectsOffsetBeyondIntegerRange() {
        assertInvalidPagination(107_374_183L, 20);
    }

    @Test
    @DisplayName("가장 큰 long 페이지 번호도 오버플로 없이 거부한다")
    void rejectsMaximumLongPage() {
        assertInvalidPagination(Long.MAX_VALUE, 100);
    }

    @Test
    @DisplayName("offset 허용 범위 안의 마지막 페이지는 허용한다")
    void acceptsLastPageWithinOffsetRange() {
        BankTransactionSearchCondition condition = condition(107_374_182L, 20);

        assertThat(condition.offset()).isEqualTo(2_147_483_640L);
    }

    @Test
    @DisplayName("offset이 int 최댓값인 경우까지 허용한다")
    void acceptsMaximumIntegerOffset() {
        BankTransactionSearchCondition condition = condition(2_147_483_647L, 1);

        assertThat(condition.offset()).isEqualTo(2_147_483_647L);
    }

    @Test
    @DisplayName("음수 페이지와 잘못된 크기의 기존 기본값 보정은 유지한다")
    void preservesDefaultNormalization() {
        BankTransactionSearchCondition condition = condition(-1, 0);

        assertThat(condition.page()).isZero();
        assertThat(condition.size()).isEqualTo(20);
        assertThat(condition.offset()).isZero();
    }

    @Test
    @DisplayName("크기를 기본값으로 보정한 다음 offset 범위를 검사한다")
    void validatesOffsetAfterSizeNormalization() {
        assertInvalidPagination(107_374_183L, 0);
    }

    private void assertInvalidPagination(long page, long size) {
        assertThatThrownBy(() -> condition(page, size))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BankTransactionErrorCode.INVALID_PAGINATION));
    }

    private BankTransactionSearchCondition condition(long page, long size) {
        return new BankTransactionSearchCondition(null, null, null, null, null, page, size);
    }
}
