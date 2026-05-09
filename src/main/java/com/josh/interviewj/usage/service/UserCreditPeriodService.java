package com.josh.interviewj.usage.service;

import com.josh.interviewj.usage.model.ChargeBucket;
import com.josh.interviewj.usage.repository.UserCreditPeriodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserCreditPeriodService {

    private final UserCreditPeriodRepository repository;

    public void incrementUsedCredits(
            Long userId,
            ChargeBucket chargeBucket,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            long chargedCreditsMicros
    ) {
        long resume = chargeBucket == ChargeBucket.RESUME_CREDITS ? chargedCreditsMicros : 0L;
        long kbQuery = chargeBucket == ChargeBucket.KB_QUERY_CREDITS ? chargedCreditsMicros : 0L;
        long kbIngestion = chargeBucket == ChargeBucket.KB_INGESTION_CREDITS ? chargedCreditsMicros : 0L;
        long interview = chargeBucket == ChargeBucket.INTERVIEW_CREDITS ? chargedCreditsMicros : 0L;

        repository.upsertIncrement(
                userId,
                "MONTHLY",
                periodStart,
                periodEnd,
                resume,
                kbQuery,
                kbIngestion,
                interview
        );
    }
}
