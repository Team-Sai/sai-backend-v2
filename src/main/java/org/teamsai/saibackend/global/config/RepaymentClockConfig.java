package org.teamsai.saibackend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class RepaymentClockConfig {

    @Bean("repaymentClock")
    public Clock repaymentClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}