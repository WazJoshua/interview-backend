package com.josh.interviewj.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationVersionUniquenessTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^(V\\d+)__.+\\.sql$");

    @Test
    void versionedMigrationsUseUniqueVersions() throws IOException {
        List<String> fileNames = Files.list(MIGRATION_DIR)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();

        Set<String> seenVersions = new LinkedHashSet<>();
        Set<String> duplicateVersions = new LinkedHashSet<>();

        for (String fileName : fileNames) {
            Matcher matcher = VERSIONED_MIGRATION.matcher(fileName);
            if (!matcher.matches()) {
                continue;
            }
            String version = matcher.group(1);
            if (!seenVersions.add(version)) {
                duplicateVersions.add(version);
            }
        }

        assertThat(duplicateVersions).isEmpty();
    }
}
