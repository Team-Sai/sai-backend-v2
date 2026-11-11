package org.teamsai.saibackend.domain.contract.dto.request;

import jakarta.validation.constraints.NotNull;
import org.teamsai.saibackend.domain.contract.type.EarlyRepaymentMethod;

public record EarlyRepaymentRequest(

        @NotNull(message = "입금 내역을 선택해 주세요.")
        Long bankTransactionId,

        // true면 갚을 금액을 넘는 만큼은 반환으로 기록한다.
        boolean returnExcess,

        // 처리 방식. 없으면 원금 먼저 갚기(RECALCULATE)
        EarlyRepaymentMethod method
) {
        public EarlyRepaymentRequest(Long bankTransactionId) {
                this(bankTransactionId, false, null);
        }

        public EarlyRepaymentRequest(Long bankTransactionId, boolean returnExcess) {
                this(bankTransactionId, returnExcess, null);
        }

        /** 가까운 회차부터 미리 내는 방식인지 */
        public boolean prepay() {
                return method == EarlyRepaymentMethod.PREPAY;
        }
}