package com.josh.interviewj.common.job;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.josh.interviewj.interview.service.InterviewTimeoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class InterviewTimeoutJobTest {

    @Mock
    private InterviewTimeoutService interviewTimeoutService;

    private InterviewTimeoutJob interviewTimeoutJob;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        interviewTimeoutJob = new InterviewTimeoutJob(interviewTimeoutService);
        ReflectionTestUtils.setField(interviewTimeoutJob, "enabled", true);
        ReflectionTestUtils.setField(interviewTimeoutJob, "batchSize", 50);
        logger = (Logger) LoggerFactory.getLogger(InterviewTimeoutJob.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void drainOnStartup_UsesConfiguredBatchSizeWhenEnabled() {
        when(interviewTimeoutService.abortTimedOutSessions(PageRequest.of(0, 50), null))
                .thenReturn(new InterviewTimeoutService.AbortTimedOutResult(0, 0, 0, 0));

        interviewTimeoutJob.drainOnStartup();

        verify(interviewTimeoutService).abortTimedOutSessions(PageRequest.of(0, 50), null);
    }

    @Test
    void drainBacklog_WhenDisabled_DoesNotInvokeService() {
        ReflectionTestUtils.setField(interviewTimeoutJob, "enabled", false);

        interviewTimeoutJob.drainBacklog();

        verify(interviewTimeoutService, never()).abortTimedOutSessions(PageRequest.of(0, 50), null);
    }

    @Test
    void drainBacklog_LogsSummaryWhenAnyCountIsNonZero() {
        when(interviewTimeoutService.abortTimedOutSessions(PageRequest.of(0, 50), null))
                .thenReturn(new InterviewTimeoutService.AbortTimedOutResult(4, 2, 1, 1));

        interviewTimeoutJob.drainBacklog();

        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("scannedCount=4")
                        && message.contains("abortedCount=2")
                        && message.contains("skippedCount=1")
                        && message.contains("failureCount=1"));
    }
}
