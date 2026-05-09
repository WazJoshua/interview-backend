package com.josh.interviewj.billing.activation.service;

import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;

@Service
public class ActivationCodeFormatService {

    private static final String SAFE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int RANDOM_LENGTH = 8;
    private static final int PREFIX_LENGTH = 3;

    private final SecureRandom random;

    public ActivationCodeFormatService(SecureRandom random) {
        this.random = random;
    }

    public String generate(ActivationCodeType type) {
        String prefix = switch (type) {
            case SUBSCRIPTION -> "SUB";
            case CREDIT -> "CRD";
        };
        StringBuilder builder = new StringBuilder(prefix);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            builder.append(SAFE_CHARS.charAt(random.nextInt(SAFE_CHARS.length())));
        }
        return builder.toString();
    }

    public String normalize(String input) {
        return input == null
                ? null
                : input.trim().replaceAll("[-\\s]", "").toUpperCase(Locale.ROOT);
    }

    public String format(String normalizedCode) {
        String prefix = normalizedCode.substring(0, PREFIX_LENGTH);
        String rest = normalizedCode.substring(PREFIX_LENGTH);
        StringBuilder builder = new StringBuilder(prefix);
        for (int i = 0; i < rest.length(); i++) {
            if (i % 4 == 0) {
                builder.append('-');
            }
            builder.append(rest.charAt(i));
        }
        return builder.toString();
    }
}
