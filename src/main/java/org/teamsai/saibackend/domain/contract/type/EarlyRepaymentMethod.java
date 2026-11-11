package org.teamsai.saibackend.domain.contract.type;

/** 중도상환 처리 방식 */
public enum EarlyRepaymentMethod {
    RECALCULATE, // 원금을 먼저 갚고 남은 회차 금액을 다시 계산
    PREPAY       // 가까운 회차부터 원래 금액대로 차례로 납부
}