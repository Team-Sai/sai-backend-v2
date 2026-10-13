package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.controller.ContractDashboardQueryController;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardResponse;
import org.teamsai.saibackend.domain.contract.service.ContractDashboardQueryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractDashboardQueryControllerTest {

    @Mock
    private ContractDashboardQueryService contractDashboardQueryService;

    @InjectMocks
    private ContractDashboardQueryController dashboardQueryController;

    @Test
    @DisplayName("전달받은 파라미터를 그대로 Service에 넘기고 결과를 반환한다")
    void getDashboard_delegatesToServiceWithParams() {
        Long userId = 1L;
        String keyword = "생활비";
        String roleFilter = "LENT";
        String statusFilter = "ONGOING";
        String sortType = "AMOUNT_DESC";
        int page = 2;

        ContractDashboardResponse expected = ContractDashboardResponse.builder()
                .currentPage(2)
                .totalPages(3)
                .totalCount(12L)
                .pageSize(5)
                .build();

        when(contractDashboardQueryService.getDashboard(userId, keyword, roleFilter, statusFilter, sortType, page))
                .thenReturn(expected);

        ContractDashboardResponse actual = dashboardQueryController.getDashboard(userId, keyword, roleFilter, statusFilter, sortType, page);

        assertThat(actual).isEqualTo(expected);
        verify(contractDashboardQueryService).getDashboard(userId, keyword, roleFilter, statusFilter, sortType, page);
    }

    @Test
    @DisplayName("page 기본값은 1이다")
    void getDashboard_defaultPageIsOne() {
        Long userId = 1L;

        when(contractDashboardQueryService.getDashboard(userId, null, null, null, null, 1))
                .thenReturn(ContractDashboardResponse.builder().currentPage(1).build());

        ContractDashboardResponse actual = dashboardQueryController.getDashboard(userId, null, null, null, null, 1);

        assertThat(actual.getCurrentPage()).isEqualTo(1);
    }
}
