package com.josh.interviewj.llm.support;

import com.josh.interviewj.llm.routing.DatabaseLlmRouteSnapshotService;
import com.josh.interviewj.usage.model.LlmConfigVersion;
import com.josh.interviewj.usage.repository.LlmConfigVersionRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmConfigCacheServiceTest {

    @Test
    void getSnapshot_ReusesLocalCacheWhenVersionUnchanged() {
        LlmConfigVersionRepository versionRepository = mock(LlmConfigVersionRepository.class);
        DatabaseLlmRouteSnapshotService snapshotService = mock(DatabaseLlmRouteSnapshotService.class);
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot snapshot = new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );

        when(versionRepository.findById("GLOBAL"))
                .thenReturn(Optional.of(LlmConfigVersion.builder().singletonKey("GLOBAL").currentVersion(5L).build()));
        when(snapshotService.loadSnapshot()).thenReturn(snapshot);

        LlmConfigCacheService cacheService = new LlmConfigCacheService(versionRepository, snapshotService);

        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot first = cacheService.getSnapshot();
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot second = cacheService.getSnapshot();

        assertThat(first).isSameAs(snapshot);
        assertThat(second).isSameAs(snapshot);
        verify(versionRepository, times(1)).findById("GLOBAL");
        verify(snapshotService, times(1)).loadSnapshot();
    }

    @Test
    void refreshIfVersionChanged_ReloadsSnapshotWhenDatabaseVersionAdvances() {
        LlmConfigVersionRepository versionRepository = mock(LlmConfigVersionRepository.class);
        DatabaseLlmRouteSnapshotService snapshotService = mock(DatabaseLlmRouteSnapshotService.class);
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot firstSnapshot = new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot secondSnapshot = new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );

        when(versionRepository.findById("GLOBAL"))
                .thenReturn(Optional.of(LlmConfigVersion.builder().singletonKey("GLOBAL").currentVersion(5L).build()))
                .thenReturn(Optional.of(LlmConfigVersion.builder().singletonKey("GLOBAL").currentVersion(6L).build()));
        when(snapshotService.loadSnapshot()).thenReturn(firstSnapshot, secondSnapshot);

        LlmConfigCacheService cacheService = new LlmConfigCacheService(versionRepository, snapshotService);

        assertThat(cacheService.getSnapshot()).isSameAs(firstSnapshot);
        assertThat(cacheService.refreshIfVersionChanged()).isTrue();
        assertThat(cacheService.getSnapshot()).isSameAs(secondSnapshot);

        verify(versionRepository, times(2)).findById("GLOBAL");
        verify(snapshotService, times(2)).loadSnapshot();
    }

    @Test
    void invalidate_ClearsLocalCacheAndForcesReload() {
        LlmConfigVersionRepository versionRepository = mock(LlmConfigVersionRepository.class);
        DatabaseLlmRouteSnapshotService snapshotService = mock(DatabaseLlmRouteSnapshotService.class);
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot firstSnapshot = new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
        DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot secondSnapshot = new DatabaseLlmRouteSnapshotService.DatabaseRouteSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );

        when(versionRepository.findById("GLOBAL"))
                .thenReturn(Optional.of(LlmConfigVersion.builder().singletonKey("GLOBAL").currentVersion(5L).build()))
                .thenReturn(Optional.of(LlmConfigVersion.builder().singletonKey("GLOBAL").currentVersion(5L).build()));
        when(snapshotService.loadSnapshot()).thenReturn(firstSnapshot, secondSnapshot);

        LlmConfigCacheService cacheService = new LlmConfigCacheService(versionRepository, snapshotService);

        assertThat(cacheService.getSnapshot()).isSameAs(firstSnapshot);
        cacheService.invalidate();
        assertThat(cacheService.getSnapshot()).isSameAs(secondSnapshot);

        verify(versionRepository, times(2)).findById("GLOBAL");
        verify(snapshotService, times(2)).loadSnapshot();
    }
}
