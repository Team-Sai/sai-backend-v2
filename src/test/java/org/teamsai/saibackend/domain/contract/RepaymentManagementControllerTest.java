package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.controller.RepaymentManagementController;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;
import org.teamsai.saibackend.domain.contract.service.RepaymentManagementService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RepaymentManagementControllerTest {

    @Test
    void delegatesToAnalysisService() {
        RepaymentAnalysisService service =
                mock(RepaymentAnalysisService.class);

        RepaymentManagementService managementService =
                mock(RepaymentManagementService.class);

        RepaymentManagementController controller =
                new RepaymentManagementController(service, managementService);
        RepaymentAnalysisContext expected =
                new RepaymentAnalysisContext(
                        LocalDate.of(2026, 10, 20),
                        YearMonth.of(2026, 10),
                        BigDecimal.valueOf(420_000),
                        BigDecimal.valueOf(80_000),
                        BigDecimal.valueOf(500_000),
                        BigDecimal.valueOf(1_000_000),
                        List.of()
                );

        when(service.analyze(1L)).thenReturn(expected);

        RepaymentAnalysisContext actual =
                controller.getRepaymentManagement(1L);

        assertThat(actual).isSameAs(expected);
        verify(service).analyze(1L);
    }

    @Test
    void delegatesPlanRequestToManagementService() {
        RepaymentAnalysisService analysisService =
                mock(RepaymentAnalysisService.class);

        RepaymentManagementService managementService =
                mock(RepaymentManagementService.class);

        RepaymentManagementController controller =
                new RepaymentManagementController(
                        analysisService, managementService);

        RepaymentManagementResponse expected =
                mock(RepaymentManagementResponse.class);

        when(managementService.getManagement(1L)).thenReturn(expected);

        RepaymentManagementResponse actual =
                controller.getRepaymentPlan(1L);

        assertThat(actual).isSameAs(expected);
        verify(managementService).getManagement(1L);
        verifyNoInteractions(analysisService);
    }
}