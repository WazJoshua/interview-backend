package com.josh.interviewj.interview.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.interview.dto.response.InterviewQuestionItemResponse;
import com.josh.interviewj.interview.dto.response.InterviewQuestionsResponse;
import com.josh.interviewj.interview.model.InterviewAnswer;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.model.InterviewSession;
import com.josh.interviewj.interview.repository.InterviewAnswerRepository;
import com.josh.interviewj.interview.repository.InterviewQuestionRepository;
import com.josh.interviewj.interview.repository.InterviewSessionRepository;
import com.josh.interviewj.interview.support.InterviewEvaluationDetailsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewQueryService {

    private final UserRepository userRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final InterviewAnswerRepository interviewAnswerRepository;
    private final InterviewEvaluationDetailsMapper evaluationDetailsMapper;

    public InterviewQuestionsResponse getQuestions(String username, UUID interviewId) {
        User user = requireUser(username);
        InterviewSession session = interviewSessionRepository.findByExternalIdAndUserIdAndDeletedAtIsNull(interviewId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "Interview not found"));
        List<InterviewQuestion> questions = interviewQuestionRepository.findBySessionIdOrderBySequenceNumberAsc(session.getId());
        Map<Long, UUID> questionExternalIds = questions.stream()
                .collect(Collectors.toMap(InterviewQuestion::getId, InterviewQuestion::getExternalId));
        Map<Long, InterviewAnswer> answersByQuestionId = interviewAnswerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .collect(Collectors.toMap(InterviewAnswer::getQuestionId, Function.identity()));

        return new InterviewQuestionsResponse(questions.stream()
                .map(question -> toQuestionItem(question, questionExternalIds, answersByQuestionId.get(question.getId())))
                .toList());
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_004, "User not found"));
    }

    private InterviewQuestionItemResponse toQuestionItem(
            InterviewQuestion question,
            Map<Long, UUID> questionExternalIds,
            InterviewAnswer answer
    ) {
        return new InterviewQuestionItemResponse(
                question.getExternalId(),
                question.getQuestionKind().name(),
                question.getFollowUpIntent() == null ? null : question.getFollowUpIntent().name(),
                question.getParentQuestionId() == null ? null : questionExternalIds.get(question.getParentQuestionId()),
                question.getBranchDepth(),
                question.getSequenceNumber(),
                question.getQuestionType(),
                question.getQuestionContent(),
                question.getDifficulty(),
                question.getEstimatedMinutes(),
                toAnswerProjection(answer)
        );
    }

    private InterviewQuestionItemResponse.AnswerProjection toAnswerProjection(InterviewAnswer answer) {
        if (answer == null) {
            return null;
        }
        return new InterviewQuestionItemResponse.AnswerProjection(
                answer.getExternalId(),
                answer.getAnswerContent(),
                answer.getDurationSeconds(),
                answer.getEvaluationScore(),
                evaluationDetailsMapper.normalizeToEnvelope(answer.getEvaluationDetails())
        );
    }
}
