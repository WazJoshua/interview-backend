package com.josh.interviewj.llm.support;

import com.josh.interviewj.usage.model.LlmConfigChangeOutbox;
import com.josh.interviewj.usage.model.LlmConfigVersion;
import com.josh.interviewj.usage.repository.LlmConfigChangeOutboxRepository;
import com.josh.interviewj.usage.repository.LlmConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LlmConfigChangeService {

    private final LlmConfigVersionRepository llmConfigVersionRepository;
    private final LlmConfigChangeOutboxRepository llmConfigChangeOutboxRepository;

    @Transactional
    public long recordChange(String changeType, String payload) {
        LlmConfigVersion configVersion = llmConfigVersionRepository.findById(LlmConfigCacheService.GLOBAL_CONFIG_KEY)
                .orElseGet(() -> LlmConfigVersion.builder()
                        .singletonKey(LlmConfigCacheService.GLOBAL_CONFIG_KEY)
                        .currentVersion(0L)
                        .build());

        long nextVersion = (configVersion.getCurrentVersion() == null ? 0L : configVersion.getCurrentVersion()) + 1;
        configVersion.setCurrentVersion(nextVersion);
        llmConfigVersionRepository.save(configVersion);

        llmConfigChangeOutboxRepository.save(LlmConfigChangeOutbox.builder()
                .configVersion(nextVersion)
                .changeType(changeType)
                .payload(payload)
                .publishStatus("PENDING")
                .publishAttempts(0)
                .build());

        return nextVersion;
    }
}
