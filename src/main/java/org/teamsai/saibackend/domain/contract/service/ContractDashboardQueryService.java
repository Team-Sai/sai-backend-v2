package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.assembler.ContractDashboardAssembler;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardRowResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardSummaryResponse;
import org.teamsai.saibackend.domain.contract.exception.ContractDashboardErrorCode;
import org.teamsai.saibackend.domain.contract.type.ContractRole;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;

import java.util.*;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractDashboardQueryService {

    private final LoanContractService loanContractService;
    private final RepaymentScheduleService repaymentScheduleService;

    private List<LoanContractResponse> getVisibleContracts(Long userId) {
        List<LoanContractResponse> contract = loanContractService.findContractsByUser(userId);

        Set<Long> supersededIds = contract.stream()
                .filter(c -> c.getPreviousContractId() != null && c.getStatus() == ContractStatus.COMPLETED)
                .map(c -> c.getPreviousContractId())
                .collect(Collectors.toSet());

        return contract.stream()
                .filter(c -> !supersededIds.contains(c.getContractId()) && c.getStatus() == ContractStatus.COMPLETED)
                .toList();
    }

    private List<ContractDashboardRowResponse> filterByKeyword(List<ContractDashboardRowResponse> rows, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return rows;
        }
        String lowerKeyword = keyword.toLowerCase();

        return rows.stream()
                .filter(c -> c.getContractAlias() != null &&
                        c.getContractAlias().toLowerCase().contains(lowerKeyword))
                .toList();
    }

    private List<ContractDashboardRowResponse> filterByRole(List<ContractDashboardRowResponse> rows, String filterType) {
        if (filterType == null || filterType.equals("ALL")) {
            return rows;
        } else if (filterType.equals("LENT")) {
            return rows.stream()
                    .filter(c -> c.getRole() == ContractRole.CREDITOR)
                    .toList();
        } else if (filterType.equals("BORROWED")) {
            return rows.stream()
                    .filter(c -> c.getRole() == ContractRole.DEBTOR)
                    .toList();
        }
        throw ContractDashboardErrorCode.INVALID_ROLE_FILTER.toException();
    }

    private List<ContractDashboardRowResponse> filterByStatus(List<ContractDashboardRowResponse> rows, String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank() || statusFilter.equals("ALL")) return rows;
        if (statusFilter.equals("ONGOING")) return rows.stream()
                .filter(row -> !"COMPLETED".equals(row.getRepaymentStatus())).toList();
        if (statusFilter.equals("COMPLETED")) return rows.stream()
                .filter(row -> "COMPLETED".equals(row.getRepaymentStatus())).toList();
        throw ContractDashboardErrorCode.INVALID_STATUS_FILTER.toException();
    }

    private List<ContractDashboardRowResponse> sortRows(List<ContractDashboardRowResponse> rows, String sortType) {
        List<ContractDashboardRowResponse> sorted = new ArrayList<>(rows);

        if (sortType == null || sortType.isEmpty()) {
            sorted.sort(Comparator.comparing(ContractDashboardRowResponse::getContractId).reversed());
            return sorted;
        }

        switch (sortType) {
            case "ALPHABET" -> sorted.sort(Comparator.comparing(ContractDashboardRowResponse::getContractAlias));
            case "ROLE" -> sorted.sort(Comparator.comparing(ContractDashboardRowResponse::getRole));
            case "CATEGORY" -> sorted.sort(Comparator.comparing(ContractDashboardRowResponse::getCategory));
            case "AMOUNT_DESC" -> sorted.sort(Comparator.comparing(ContractDashboardRowResponse::getTotalRemainingAmount).reversed());
            case "AMOUNT_ASC" -> sorted.sort(Comparator.comparing(ContractDashboardRowResponse::getTotalRemainingAmount));
            case "STATUS" -> sorted.sort(Comparator.comparing(ContractDashboardRowResponse::getContractStatus));
            case "DEADLINE" -> sorted.sort(Comparator.comparing(ContractDashboardRowResponse::getMaturityDate));
            case "CREATED_DESC" -> sorted.sort(Comparator.comparing(
                    ContractDashboardRowResponse::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
            ));
            default -> throw ContractDashboardErrorCode.INVALID_SORT_TYPE.toException();
        }
        return sorted;
    }

    private List<ContractDashboardRowResponse> paginate(List<ContractDashboardRowResponse> rows, int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }

        int startIndex = (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, rows.size());

        if (startIndex >= rows.size()) {
            return List.of();
        }
        return rows.subList(startIndex, endIndex);
    }

    private List<ContractScheduleContext> getContractScheduleContexts(Long userId) {
        List<LoanContractResponse> contracts = getVisibleContracts(userId);
        List<Long> contractIds = contracts.stream()
                .map(LoanContractResponse::getContractId)
                .toList();
        Map<Long, List<RepaymentScheduleWithRemainingProjection>> scheduleMap =
                repaymentScheduleService.getSchedulesByContractIds(contractIds);

        return contracts.stream()
                .map(contract -> new ContractScheduleContext(
                        contract,
                        scheduleMap.getOrDefault(contract.getContractId(), List.of())
                ))
                .toList();
    }

    private ContractDashboardResponse buildDashboard(
            List<ContractScheduleContext> contexts,
            Long userId,
            String keyword,
            String roleFilter,
            String statusFilter,
            String sortType,
            int page
    ) {
        Map<Long, List<RepaymentScheduleWithRemainingProjection>> scheduleMap = contexts.stream()
                .collect(Collectors.toMap(
                        context -> context.contract().getContractId(),
                        ContractScheduleContext::schedules
                ));

        List<ContractDashboardRowResponse> allRows = contexts.stream()
                .map(context -> ContractDashboardAssembler.toRow(context.contract(), context.schedules(), userId))
                .toList();

        ContractDashboardSummaryResponse summary = ContractDashboardAssembler.buildSummary(allRows, scheduleMap);
        List<ContractDashboardRowResponse> filtered = filterByKeyword(allRows, keyword);
        List<ContractDashboardRowResponse> roleFiltered = filterByRole(filtered, roleFilter);
        List<ContractDashboardRowResponse> sorted = sortRows(filterByStatus(roleFiltered, statusFilter), sortType);
        long totalCount = sorted.size();
        List<ContractDashboardRowResponse> pagedRows = paginate(sorted, page, 5);
        int totalPages = (int) Math.ceil((double) totalCount / 5);

        return ContractDashboardResponse.builder()
                .summary(summary)
                .contracts(pagedRows)
                .currentPage(page)
                .totalPages(totalPages)
                .totalCount(totalCount)
                .pageSize(5)
                .build();
    }

    public ContractDashboardResponse getDashboard(
            Long userId, String keyword, String roleFilter,
            String statusFilter, String sortType, int page
    ) {
        return buildDashboard(
                getContractScheduleContexts(userId),
                userId, keyword, roleFilter, statusFilter, sortType, page
        );
    }

    public IntegrationDashboardData getIntegrationDashboardData(Long userId) {
        List<ContractScheduleContext> contexts = getContractScheduleContexts(userId);
        ContractDashboardResponse dashboard = buildDashboard(
                contexts,
                userId,
                null,
                "ALL",
                "ALL",
                "CREATED_DESC",
                1
        );
        List<LoanScheduleContext> loanSchedules = contexts.stream()
                .flatMap(context -> context.schedules().stream()
                        .map(schedule -> new LoanScheduleContext(context.contract(), schedule)))
                .toList();
        return new IntegrationDashboardData(dashboard, loanSchedules);
    }

    private record ContractScheduleContext(
            LoanContractResponse contract,
            List<RepaymentScheduleWithRemainingProjection> schedules
    ) {
    }

    public record LoanScheduleContext(
            LoanContractResponse contract,
            RepaymentScheduleWithRemainingProjection schedule
    ) {
    }

    public record IntegrationDashboardData(
            ContractDashboardResponse dashboard,
            List<LoanScheduleContext> loanSchedules
    ) {
    }

}
