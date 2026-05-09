package com.josh.interviewj.interview.repository;

import com.josh.interviewj.interview.model.InterviewAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswer, Long> {

    Optional<InterviewAnswer> findByExternalId(UUID externalId);

    Optional<InterviewAnswer> findBySessionIdAndQuestionId(Long sessionId, Long questionId);

    List<InterviewAnswer> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
