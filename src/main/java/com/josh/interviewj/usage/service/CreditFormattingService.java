package com.josh.interviewj.usage.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class CreditFormattingService {

    public String formatCreditsMicros(Long micros) {
        long safeMicros = micros == null ? 0L : micros;
        return BigDecimal.valueOf(safeMicros, 3)
                .setScale(3, RoundingMode.HALF_UP)
                .toPlainString();
    }

    public String formatCreditsMicrosNullable(Long micros) {
        return micros == null ? null : formatCreditsMicros(micros);
    }

    public String formatRatio(BigDecimal ratio) {
        return ratio == null ? null : ratio.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    public String formatAmount(BigDecimal amount) {
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount;
        return safeAmount.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    public long remainingMicros(long limitMicros, long usedMicros) {
        return Math.max(limitMicros - usedMicros, 0L);
    }
}
