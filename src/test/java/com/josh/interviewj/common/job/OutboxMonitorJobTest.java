package com.josh.interviewj.common.job;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.josh.interviewj.common.enums.OutboxStatus;
import com.josh.interviewj.knowledgebase.repository.KbDocumentOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeAnalysisOutboxRepository;
import com.josh.interviewj.resume.repository.ResumeParseOutboxRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMonitorJobTest {

    @Test
    void checkOutboxBacklog_LogsAllThreePipelines() {
        ResumeParseOutboxRepository parseRepository = mock(ResumeParseOutboxRepository.class);
        KbDocumentOutboxRepository kbRepository = mock(KbDocumentOutboxRepository.class);
        ResumeAnalysisOutboxRepository analysisRepository = mock(ResumeAnalysisOutboxRepository.class);
        when(parseRepository.countByStatusIn(List.of(OutboxStatus.NEW, OutboxStatus.RETRY))).thenReturn(101L);
        when(kbRepository.countByStatusIn(List.of(OutboxStatus.NEW, OutboxStatus.RETRY))).thenReturn(102L);
        when(analysisRepository.countByStatusIn(List.of(OutboxStatus.NEW, OutboxStatus.RETRY))).thenReturn(103L);

        OutboxMonitorJob job = new OutboxMonitorJob(parseRepository, kbRepository, analysisRepository);
        setPrivateInt(job, "backlogAlertThreshold", 100);

        Logger logger = (Logger) LoggerFactory.getLogger(OutboxMonitorJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            job.checkOutboxBacklog();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsOnly(Level.WARN);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("pipeline=resume-parse"))
                .anySatisfy(message -> assertThat(message).contains("pipeline=kb-doc"))
                .anySatisfy(message -> assertThat(message).contains("pipeline=resume-analysis"));
    }

    private static void setPrivateInt(Object target, String fieldName, int value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
