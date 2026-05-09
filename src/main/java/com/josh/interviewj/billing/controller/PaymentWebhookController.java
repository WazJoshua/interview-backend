package com.josh.interviewj.billing.controller;

import com.josh.interviewj.billing.service.PaymentWebhookService;
import com.josh.interviewj.billing.provider.ProviderWebhookResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payment/webhooks")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    @PostMapping("/{provider}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable String provider,
            @RequestBody String payload,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request
    ) {
        ProviderWebhookResponse response = paymentWebhookService.handleWebhook(provider, payload, mergeHeaders(headers, request));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(response.body());
    }

    private Map<String, String> mergeHeaders(Map<String, String> headers, HttpServletRequest request) {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return merged;
        }
        for (String headerName : Collections.list(headerNames)) {
            merged.putIfAbsent(headerName, request.getHeader(headerName));
        }
        return merged;
    }
}
