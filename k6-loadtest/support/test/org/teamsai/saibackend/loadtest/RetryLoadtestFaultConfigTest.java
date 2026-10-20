package org.teamsai.saibackend.loadtest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.teamsai.saibackend.domain.payment.service.LoanPaymentService;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;
import org.teamsai.saibackend.global.exception.DomainException;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetryLoadtestFaultConfigTest {
    @Test
    @SuppressWarnings("unchecked")
    void injectsOnlyMarkedAutomaticPaymentAndPreservesOtherBeans() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(jdbc);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(123L)))
                .thenReturn(List.of("BRT:persistent-failed"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(124L)))
                .thenReturn(List.of("normal"));
        var processor = RetryLoadtestFaultConfig.retryLoadtestPaymentFaults(provider);
        var service = mock(LoanPaymentService.class);
        var proxy = (LoanPaymentService) processor.postProcessAfterInitialization(service, "loanPaymentService");
        var failure = assertThrows(DomainException.class,
                () -> proxy.applyAutoMatchedPayment(1L, 123L, BigDecimal.TEN));
        assertEquals(PaymentErrorCode.PAYMENT_STATUS_UPDATE_FAILED, failure.getErrorCode());
        verify(service, never()).applyAutoMatchedPayment(1L, 123L, BigDecimal.TEN);
        proxy.applyAutoMatchedPayment(1L, 124L, BigDecimal.TEN);
        verify(service).applyAutoMatchedPayment(1L, 124L, BigDecimal.TEN);
        assertSame(service, processor.postProcessAfterInitialization(service, "otherBean"));
    }
}
