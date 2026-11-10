package org.teamsai.saibackend.domain.contract.dto.response;

import java.math.BigDecimal;

/** 오늘 한 번에 갚을 때의 금액: 남은 원금 + 오늘까지 이자 + 밀린 회차 */
public record PayoffResponse(
        BigDecimal principal,
        BigDecimal interest,
        BigDecimal shortfall,
        BigDecimal total
) {
}