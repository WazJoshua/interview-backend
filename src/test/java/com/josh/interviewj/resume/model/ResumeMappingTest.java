package com.josh.interviewj.resume.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ResumeMappingTest {

    @Test
    void analysisStatusColumn_ShouldBeNotNull() throws NoSuchFieldException {
        Field field = Resume.class.getDeclaredField("analysisStatus");
        Column column = field.getAnnotation(Column.class);

        assertNotNull(column);
        assertFalse(column.nullable());
    }

    @Test
    void parsedAtColumn_ShouldMapToParsedAt() throws NoSuchFieldException {
        Field field = Resume.class.getDeclaredField("parsedAt");
        Column column = field.getAnnotation(Column.class);

        assertNotNull(column);
        assertEquals("parsed_at", column.name());
    }
}
