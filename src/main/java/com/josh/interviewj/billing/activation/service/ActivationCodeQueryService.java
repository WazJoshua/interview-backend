package com.josh.interviewj.billing.activation.service;

import com.josh.interviewj.billing.activation.dto.response.ActivationCodeStatsResponse;
import com.josh.interviewj.billing.activation.dto.response.ActivationCodeView;
import com.josh.interviewj.billing.activation.model.ActivationCode;
import com.josh.interviewj.billing.activation.model.ActivationCodeStatus;
import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import com.josh.interviewj.billing.activation.repository.ActivationCodeRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActivationCodeQueryService {

    private static final String[] CSV_HEADERS = {
            "id",
            "code",
            "codeType",
            "status",
            "billingPlanVersionId",
            "subscriptionDurationDays",
            "creditAmountMicros",
            "expiresAt",
            "redeemedByUserId",
            "redeemedAt",
            "batchId",
            "createdByUserId",
            "note",
            "createdAt"
    };

    private final ActivationCodeRepository activationCodeRepository;
    private final ActivationCodeFormatService formatService;

    public Page<ActivationCodeView> list(
            ActivationCodeStatus status,
            ActivationCodeType codeType,
            UUID batchId,
            Long createdByUserId,
            LocalDateTime createdFrom,
            LocalDateTime createdTo,
            Pageable pageable
    ) {
        return activationCodeRepository.findByFilters(status, codeType, batchId, createdByUserId, createdFrom, createdTo, pageable)
                .map(this::toView);
    }

    public ActivationCodeStatsResponse stats() {
        long unused = activationCodeRepository.countByStatus(ActivationCodeStatus.UNUSED);
        long redeemed = activationCodeRepository.countByStatus(ActivationCodeStatus.REDEEMED);
        long voided = activationCodeRepository.countByStatus(ActivationCodeStatus.VOIDED);
        long expired = activationCodeRepository.countByStatus(ActivationCodeStatus.EXPIRED);
        return ActivationCodeStatsResponse.builder()
                .total(unused + redeemed + voided + expired)
                .unused(unused)
                .redeemed(redeemed)
                .voided(voided)
                .expired(expired)
                .build();
    }

    public void writeCsv(
            ActivationCodeStatus status,
            ActivationCodeType codeType,
            UUID batchId,
            Long createdByUserId,
            LocalDateTime createdFrom,
            LocalDateTime createdTo,
            Writer writer
    ) throws IOException {
        List<ActivationCode> activationCodes = activationCodeRepository.findAllByFilters(
                status,
                codeType,
                batchId,
                createdByUserId,
                createdFrom,
                createdTo
        );
        try (CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(CSV_HEADERS).build())) {
            for (ActivationCode activationCode : activationCodes) {
                csvPrinter.printRecord(
                        activationCode.getId(),
                        formatService.format(activationCode.getCode()),
                        activationCode.getCodeType(),
                        activationCode.getStatus(),
                        activationCode.getBillingPlanVersionId(),
                        activationCode.getSubscriptionDurationDays(),
                        activationCode.getCreditAmountMicros(),
                        activationCode.getExpiresAt(),
                        activationCode.getRedeemedByUserId(),
                        activationCode.getRedeemedAt(),
                        activationCode.getBatchId(),
                        activationCode.getCreatedByUserId(),
                        activationCode.getNote(),
                        activationCode.getCreatedAt()
                );
            }
        }
    }

    private ActivationCodeView toView(ActivationCode activationCode) {
        return ActivationCodeView.builder()
                .id(activationCode.getId())
                .code(formatService.format(activationCode.getCode()))
                .codeType(activationCode.getCodeType())
                .status(activationCode.getStatus())
                .billingPlanVersionId(activationCode.getBillingPlanVersionId())
                .subscriptionDurationDays(activationCode.getSubscriptionDurationDays())
                .creditAmountMicros(activationCode.getCreditAmountMicros())
                .expiresAt(toOffset(activationCode.getExpiresAt()))
                .redeemedByUserId(activationCode.getRedeemedByUserId())
                .redeemedAt(toOffset(activationCode.getRedeemedAt()))
                .batchId(activationCode.getBatchId())
                .createdByUserId(activationCode.getCreatedByUserId())
                .note(activationCode.getNote())
                .createdAt(toOffset(activationCode.getCreatedAt()))
                .build();
    }

    private static OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
