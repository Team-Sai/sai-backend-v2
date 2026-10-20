// Explicitly opt-in: ./gradlew -I k6-loadtest/support/loadtest-faults.init.gradle bootRun
// --args='--spring.profiles.active=dev,loadtest-faults --loadtest.retry-faults.enabled=true'
// This source is outside src/main and is excluded from ordinary builds.
package org.teamsai.saibackend.loadtest;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;

@Configuration(proxyBeanMethods = false)
@Profile("dev & loadtest-faults")
@ConditionalOnProperty(name = "loadtest.retry-faults.enabled", havingValue = "true")
public class RetryLoadtestFaultConfig {
    @Bean
    static BeanPostProcessor retryLoadtestPaymentFaults(ObjectProvider<JdbcTemplate> jdbc) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!beanName.equals("settlementPaymentService") && !beanName.equals("loanPaymentService")) {
                    return bean;
                }
                ProxyFactory proxy = new ProxyFactory(bean);
                proxy.setProxyTargetClass(true);
                proxy.addAdvice((MethodInterceptor) invocation -> {
                    if (invocation.getMethod().getName().equals("applyAutoMatchedPayment")) {
                        Long transactionId = (Long) invocation.getArguments()[1];
                        var memos = jdbc.getObject().queryForList(
                                "SELECT memo FROM bank_transaction WHERE bank_transaction_id = ?",
                                String.class, transactionId);
                        if (memos.size() == 1 && "BRT:persistent-failed".equals(memos.get(0))) {
                            // Throw before entering the payment transaction. AutoMatchingService
                            // records FAILED while the account retry increment can commit normally.
                            throw PaymentErrorCode.PAYMENT_STATUS_UPDATE_FAILED.toException();
                        }
                    }
                    return invocation.proceed();
                });
                return proxy.getProxy();
            }
        };
    }
}
