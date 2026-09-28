package org.teamsai.saibackend.domain.integration.assembler;

import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardRowResponse;
import org.teamsai.saibackend.domain.contract.type.ContractDashboardStatus;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardRecentTransactionResponse;
import org.teamsai.saibackend.domain.integration.model.SettlementDashboardContext;
import org.teamsai.saibackend.domain.integration.type.DashboardTransactionStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DashboardRecentTransactionAssembler {
    private DashboardRecentTransactionAssembler() {
    }

    public static List<DashboardRecentTransactionResponse> toRecentTransactions(
            ContractDashboardResponse contractDashboard,
            List<SettlementDashboardContext> settlements
    ) {
        Comparator<DashboardRecentTransactionResponse> newestFirst = Comparator.comparing(
                DashboardRecentTransactionResponse::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())
        );
        List<DashboardRecentTransactionResponse> transactions = new ArrayList<>();
        recentLoanTransactions(contractDashboard).stream()
                .sorted(newestFirst)
                .limit(5)
                .forEach(transactions::add);
        settlements.stream()
                .map(DashboardRecentTransactionAssembler::toRecentSettlementTransaction)
                .sorted(newestFirst)
                .limit(5)
                .forEach(transactions::add);
        return transactions.stream()
                .sorted(newestFirst)
                .toList();
    }

    private static List<DashboardRecentTransactionResponse> recentLoanTransactions(ContractDashboardResponse contractDashboard) {
        List<ContractDashboardRowResponse> contracts = contractDashboard.getContracts();
        if (contracts == null) {
            return List.of();
        }
        return contracts.stream()
                .map(DashboardRecentTransactionAssembler::toRecentLoanTransaction)
                .toList();
    }

    private static DashboardRecentTransactionResponse toRecentLoanTransaction(ContractDashboardRowResponse contract) {
        DashboardTransactionStatus status =
                contract.getContractStatus() == ContractDashboardStatus.COMPLETED
                        ? DashboardTransactionStatus.COMPLETED
                        : DashboardTransactionStatus.IN_PROGRESS;

        return DashboardRecentTransactionResponse.builder()
                .targetId(contract.getContractId())
                .type(PaymentTargetType.LOAN)
                .title(contract.getContractAlias())
                .status(status)
                .amount(zeroIfNull(contract.getPrincipalAmount()))
                .detailUrl("/contracts/" + contract.getContractId() + "/contract-detail")
                .createdAt(contract.getCreatedAt())
                .build();
    }

    private static DashboardRecentTransactionResponse toRecentSettlementTransaction(SettlementDashboardContext context) {
        SettlementListResponse settlement = context.settlement();
        return DashboardRecentTransactionResponse.builder()
                .targetId(settlement.settlementId())
                .type(PaymentTargetType.SETTLEMENT)
                .title(settlement.title())
                .status(context.isClosed()
                        ? DashboardTransactionStatus.COMPLETED
                        : DashboardTransactionStatus.IN_PROGRESS)
                .amount(context.originalRoleAmount())
                .detailUrl("/settlements/" + settlement.settlementId())
                .createdAt(settlement.createdAt())
                .build();
    }

    private static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
