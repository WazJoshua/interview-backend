package com.josh.interviewj.billing.activation.service;

import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationCodeFormatServiceTest {

    private ActivationCodeFormatService sut;

    @BeforeEach
    void setUp() {
        sut = new ActivationCodeFormatService(new SecureRandom());
    }

    @Test
    void generateSubscriptionStartsWithSubAndHasElevenChars() {
        String code = sut.generate(ActivationCodeType.SUBSCRIPTION);

        assertThat(code).startsWith("SUB").hasSize(11).matches("^[A-Z2-9]+$");
    }

    @Test
    void generateCreditStartsWithCrdAndHasElevenChars() {
        assertThat(sut.generate(ActivationCodeType.CREDIT)).startsWith("CRD").hasSize(11);
    }

    @Test
    void generateExcludesConfusingCharacters() {
        for (int i = 0; i < 200; i++) {
            assertThat(sut.generate(ActivationCodeType.SUBSCRIPTION)).doesNotContain("0", "O", "1", "I", "L");
        }
    }

    @Test
    void normalizeRemovesHyphensSpacesAndConvertsToUpperCase() {
        assertThat(sut.normalize("sub-a2b3-c4d5")).isEqualTo("SUBA2B3C4D5");
        assertThat(sut.normalize("  SUB A2B3 C4D5  ")).isEqualTo("SUBA2B3C4D5");
    }

    @Test
    void formatInsertsHyphensCorrectly() {
        assertThat(sut.format("SUBA2B3C4D5")).isEqualTo("SUB-A2B3-C4D5");
        assertThat(sut.format("CRDA2B3C4D5")).isEqualTo("CRD-A2B3-C4D5");
    }

    @Test
    void formatRoundTrip() {
        String raw = sut.generate(ActivationCodeType.SUBSCRIPTION);

        assertThat(sut.normalize(sut.format(raw))).isEqualTo(raw);
    }
}
