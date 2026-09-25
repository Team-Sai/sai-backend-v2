package org.teamsai.saibackend.domain.contract.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ContractDashboardResponse {

    private ContractDashboardSummaryResponse summary;
    private List<ContractDashboardRowResponse> contracts;
    private int currentPage;
    private int totalPages;
    private long totalCount;
    private int pageSize;
}
