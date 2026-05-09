package com.josh.interviewj.ragqa.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimateUtilsTest {

    @Test
    void estimate_NullOrBlank_ReturnsZero() {
        assertThat(TokenEstimateUtils.estimate(null)).isZero();
        assertThat(TokenEstimateUtils.estimate("")).isZero();
        assertThat(TokenEstimateUtils.estimate("   ")).isZero();
    }

    @Test
    void estimate_Text_ReturnsLengthBasedEstimate() {
        assertThat(TokenEstimateUtils.estimate("Redis")).isEqualTo(5);
        assertThat(TokenEstimateUtils.estimate("你好JWT")).isEqualTo(5);
    }
}
