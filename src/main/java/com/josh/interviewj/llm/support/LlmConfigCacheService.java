package com.josh.interviewj.llm.support;

import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.usage.repository.LlmConfigVersionRepository;
import org.springframework.stereotype.Service;

@Service
public class LlmConfigCacheService {

    public static final String GLOBAL_CONFIG_KEY = "GLOBAL";

    private final LlmConfigVersionRepository llmConfigVersionRepository;
    private final DatabaseLlmRouteSnapshotService snapshotService;
    private final Object monitor = new Object();

    private volatile CachedSnapshot cachedSnapshot;

    public LlmConfigCacheService(
            LlmConfigVersionRepository llmConfigVersionRepository,
            DatabaseLlmRouteSnapshotService snapshotService
    ) {
        this.llmConfigVersionRepository = llmConfigVersionRepository;
        this.snapshotService = snapshotService;
    }

    public DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot getSnapshot() {
        CachedSnapshot local = cachedSnapshot;
        if (local != null) {
            return local.snapshot();
        }
        synchronized (monitor) {
            if (cachedSnapshot == null) {
                cachedSnapshot = loadSnapshot(resolveCurrentVersion());
            }
            return cachedSnapshot.snapshot();
        }
    }

    public boolean refreshIfVersionChanged() {
        long currentVersion = resolveCurrentVersion();
        CachedSnapshot local = cachedSnapshot;
        if (local != null && local.version() == currentVersion) {
            return false;
        }
        synchronized (monitor) {
            CachedSnapshot latest = cachedSnapshot;
            if (latest != null && latest.version() == currentVersion) {
                return false;
            }
            cachedSnapshot = loadSnapshot(currentVersion);
            return true;
        }
    }

    public void invalidate() {
        cachedSnapshot = null;
    }

    public Long cachedVersion() {
        CachedSnapshot local = cachedSnapshot;
        return local == null ? null : local.version();
    }

    private CachedSnapshot loadSnapshot(long version) {
        return new CachedSnapshot(version, snapshotService.loadSnapshot());
    }

    private long resolveCurrentVersion() {
        return llmConfigVersionRepository.findById(GLOBAL_CONFIG_KEY)
                .map(configVersion -> configVersion.getCurrentVersion() == null ? 0L : configVersion.getCurrentVersion())
                .orElse(0L);
    }

    private record CachedSnapshot(long version, DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot) {
    }
}
