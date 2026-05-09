package com.josh.interviewj.common.mq;

import com.josh.interviewj.common.mq.message.KbDocumentMessage;
import com.josh.interviewj.common.mq.message.ResumeAnalysisMessage;
import com.josh.interviewj.common.mq.message.ResumeParseMessage;

public interface AsyncTaskPublisher {

    PublishResult publishResumeParseTask(ResumeParseMessage message);

    PublishResult publishKbDocumentTask(KbDocumentMessage message);

    PublishResult publishResumeAnalysisTask(ResumeAnalysisMessage message);

    record PublishResult(boolean published, String failureReason) {

        public static PublishResult success() {
            return new PublishResult(true, null);
        }

        public static PublishResult failure(String failureReason) {
            return new PublishResult(false, failureReason);
        }
    }
}
