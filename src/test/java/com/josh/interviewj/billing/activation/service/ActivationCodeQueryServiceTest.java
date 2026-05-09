package com.josh.interviewj.billing.activation.service;

import com.josh.interviewj.billing.activation.dto.response.ActivationCodeStatsResponse;
import com.josh.interviewj.billing.activation.dto.response.ActivationCodeView;
import com.josh.interviewj.billing.activation.model.ActivationCode;
import com.josh.interviewj.billing.activation.model.ActivationCodeStatus;
import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import com.josh.interviewj.billing.activation.repository.ActivationCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationCodeQueryServiceTest {

    @Mock
    private ActivationCodeRepository activationCodeRepository;

    @Mock
    private ActivationCodeFormatService formatService;

    private ActivationCodeQueryService sut;

    @BeforeEach
    void setUp() {
        sut = new ActivationCodeQueryService(activationCodeRepository, formatService);
    }

    @Test
    void listReturnsPageOfViews() {
        ActivationCode code = ActivationCode.builder()
                .id(1L)
                .code("SUBABCD1234")
                .codeType(ActivationCodeType.SUBSCRIPTION)
                .status(ActivationCodeStatus.UNUSED)
                .createdByUserId(100L)
                .createdAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .build();
        Page<ActivationCode> page = new PageImpl<>(List.of(code));
        when(activationCodeRepository.findByFilters(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(page);
        when(formatService.format("SUBABCD1234")).thenReturn("SUB-ABCD-1234");

        Page<ActivationCodeView> result = sut.list(null, null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getCode()).isEqualTo("SUB-ABCD-1234");
    }

    @Test
    void statsReturnsCorrectCounts() {
        when(activationCodeRepository.countByStatus(ActivationCodeStatus.UNUSED)).thenReturn(10L);
        when(activationCodeRepository.countByStatus(ActivationCodeStatus.REDEEMED)).thenReturn(5L);
        when(activationCodeRepository.countByStatus(ActivationCodeStatus.VOIDED)).thenReturn(2L);
        when(activationCodeRepository.countByStatus(ActivationCodeStatus.EXPIRED)).thenReturn(3L);

        ActivationCodeStatsResponse result = sut.stats();

        assertThat(result.getTotal()).isEqualTo(20);
        assertThat(result.getUnused()).isEqualTo(10);
        assertThat(result.getRedeemed()).isEqualTo(5);
    }

    @Test
    void writeCsvWritesHeaderAndRows() throws Exception {
        ActivationCode code = ActivationCode.builder()
                .id(1L)
                .code("SUBABCD1234")
                .codeType(ActivationCodeType.SUBSCRIPTION)
                .status(ActivationCodeStatus.UNUSED)
                .createdByUserId(100L)
                .createdAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .build();
        when(activationCodeRepository.findAllByFilters(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(code));
        when(formatService.format("SUBABCD1234")).thenReturn("SUB-ABCD-1234");

        StringWriter writer = new StringWriter();
        sut.writeCsv(null, null, null, null, null, null, writer);

        String csv = writer.toString();
        assertThat(csv).contains("id,code,codeType,status");
        assertThat(csv).contains("SUB-ABCD-1234");
    }
}
