package com.josh.interviewj.knowledgebase.preprocessing.model;

import com.josh.interviewj.knowledgebase.preprocessing.review.ReviewProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentModelContractTest {

    @Test
    void parsedAndNormalizedDocument_CollectionsDefaultToEmpty() {
        ParsedDocument parsedDocument = ParsedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("sample.pdf")
                .build();
        NormalizedDocument normalizedDocument = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("sample.pdf")
                .build();

        assertNotNull(parsedDocument.blocks());
        assertNotNull(parsedDocument.warnings());
        assertNotNull(parsedDocument.rawMetadata());
        assertTrue(parsedDocument.blocks().isEmpty());
        assertTrue(parsedDocument.warnings().isEmpty());

        assertNotNull(normalizedDocument.blocks());
        assertNotNull(normalizedDocument.warnings());
        assertNotNull(normalizedDocument.metadata());
        assertNotNull(normalizedDocument.qualitySummary());
        assertNotNull(normalizedDocument.reviewProjection());
        assertTrue(normalizedDocument.blocks().isEmpty());
        assertTrue(normalizedDocument.warnings().isEmpty());
    }

    @Test
    void reviewProjection_DefaultsToEmptyCollections() {
        ReviewProjection projection = ReviewProjection.builder().build();
        NormalizedDocument normalizedDocument = NormalizedDocument.builder()
                .sourceType(DocumentSourceType.PDF)
                .fileName("sample.pdf")
                .build();

        assertNotNull(projection.blocks());
        assertTrue(projection.blocks().isEmpty());
        assertNotNull(normalizedDocument.reviewProjection());
        assertTrue(normalizedDocument.reviewProjection().blocks().isEmpty());
    }

    @Test
    void blockTypeContracts_ContainRequiredValues() {
        assertNotNull(ParsedBlockType.valueOf("TITLE"));
        assertNotNull(ParsedBlockType.valueOf("CODE"));
        assertNotNull(ParsedBlockType.valueOf("HEADER"));
        assertNotNull(NormalizedBlockType.valueOf("FOOTER"));
        assertNotNull(NormalizedBlockType.valueOf("UNKNOWN"));
    }

    @Test
    void qualitySummary_DefaultBuilderProducesStableBooleans() {
        DocumentQualitySummary qualitySummary = DocumentQualitySummary.empty();

        assertTrue(qualitySummary.fitForMainIndex());
        assertFalse(qualitySummary.hasStructuralWarnings());
        assertFalse(qualitySummary.hasReadabilityWarnings());
        assertNotNull(qualitySummary.metrics());
    }
}
