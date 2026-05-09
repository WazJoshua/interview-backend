package com.josh.interviewj.auth;

import com.josh.interviewj.auth.service.PasswordResetNotificationService;
import com.josh.interviewj.support.InMemoryPasswordResetNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TestPasswordResetNotificationConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestPasswordResetNotificationConfig.class);

    @Test
    void exposesSinglePasswordResetNotificationServiceCandidate() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PasswordResetNotificationService.class);
            assertThat(context).hasSingleBean(InMemoryPasswordResetNotificationService.class);
            assertThat(context.getBean(PasswordResetNotificationService.class))
                    .isSameAs(context.getBean(InMemoryPasswordResetNotificationService.class));
        });
    }
}
