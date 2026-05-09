package com.josh.interviewj.billing.provider;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentProviderRegistry {

    private final Map<String, PaymentProviderAdapter> adaptersByProvider;

    public PaymentProviderRegistry(List<PaymentProviderAdapter> adapters) {
        this.adaptersByProvider = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        adapter -> normalize(adapter.provider()),
                        Function.identity()
                ));
    }

    public PaymentProviderAdapter requireAdapter(String provider) {
        PaymentProviderAdapter adapter = adaptersByProvider.get(normalize(provider));
        if (adapter == null) {
            throw new BusinessException(ErrorCode.PAYMENT_001, "Payment provider is not registered");
        }
        return adapter;
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
