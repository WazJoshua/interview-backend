package com.josh.interviewj.interview.support;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.interview.model.InterviewAnswer;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.model.InterviewStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class InterviewConcurrencyGuard {

    public void assertCanSubmit(
            InterviewSession session,
            InterviewQuestion question,
            Optional<InterviewAnswer> existingAnswer
    ) {
        if (session.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.INTERVIEW_007, "Interview is not accepting answers");
        }
        if (session.getCurrentQuestionId() == null || !session.getCurrentQuestionId().equals(question.getId())) {
            throw new BusinessException(ErrorCode.INTERVIEW_007, "Question is not the active question");
        }
        if (existingAnswer.isPresent()) {
            throw new BusinessException(ErrorCode.INTERVIEW_006, "Question has already been answered");
        }
    }
}
