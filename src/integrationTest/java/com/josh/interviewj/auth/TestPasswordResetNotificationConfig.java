package com.josh.interviewj.auth;

import com.josh.interviewj.support.InMemoryPasswordResetNotificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Overrides password reset notifications with an in-memory capture service.
 */
@TestConfiguration
public class TestPasswordResetNotificationConfig {

    @Bean
    @Primary
    InMemoryPasswordResetNotificationService inMemoryPasswordResetNotificationService() {
        return new InMemoryPasswordResetNotificationService();
    }
}
