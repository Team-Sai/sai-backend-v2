package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.controller.RepaymentManagementController;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;

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

        RepaymentManagementController controller =
                new RepaymentManagementController(service);

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
}