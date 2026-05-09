package com.josh.interviewj.util;

import com.josh.interviewj.common.util.RegexPatterns;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegexPatternsTest {

    /**
     * Verify email extraction returns normalized and distinct values.
     */
    @Test
    void extractEmails_FindsAndNormalizesDistinctEmails() {
        String text = "Contact: Test.User+hi@Example.COM, test.user+hi@example.com; other: a_b@foo.co.uk";

        List<String> emails = RegexPatterns.extractEmails(text);

        assertEquals(List.of(
                "test.user+hi@example.com",
                "a_b@foo.co.uk"
        ), emails);
    }

    /**
     * Verify phone extraction supports common separators and country prefix.
     */
    @Test
    void extractPhones_FindsCommonCnPhonesAndNormalizes() {
        String text = "Phone: +86 138-0013-8000; backup: 13900139000";

        List<String> phones = RegexPatterns.extractPhones(text);

        assertEquals(List.of("+8613800138000", "13900139000"), phones);
    }

    /**
     * Verify date extraction prefers ranges as a single match.
     */
    @Test
    void extractDates_FindsCommonDateRanges() {
        String text = "Work: 2020.03-2023.06; Education: 2019年7月-至今";

        List<String> dates = RegexPatterns.extractDates(text);

        assertEquals(List.of("2020.03-2023.06", "2019年7月-至今"), dates);
    }
}
