package com.josh.interviewj.knowledgebase.preprocessing.lowsignal;

import com.josh.interviewj.knowledgebase.preprocessing.config.DocumentPreprocessingProperties;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlock;
import com.josh.interviewj.knowledgebase.preprocessing.model.NormalizedBlockType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowSignalBlockRuleEngineTest {

    private final DocumentPreprocessingProperties properties = new DocumentPreprocessingProperties();
    private final LowSignalBlockRuleEngine ruleEngine = new LowSignalBlockRuleEngine(
            properties,
            new LowSignalProtectionRules(),
            new LowSignalStrongDropRules(),
            new LowSignalScoreRules()
    );

    @Test
    void evaluate_ProtectsShortTechnicalBlocks() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.CODE)
                .text("./gradlew test")
                .order(0)
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(LowSignalDecisionType.PROTECT, decision.decisionType());
        assertTrue(decision.reasonCodes().contains(LowSignalReasonCode.PROTECTED_BLOCK_TYPE));
    }

    @Test
    void evaluate_DropsObviousSeparatorNoise() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.UNKNOWN)
                .text("----------")
                .order(0)
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(LowSignalDecisionType.DROP, decision.decisionType());
        assertTrue(decision.reasonCodes().contains(LowSignalReasonCode.DROP_SEPARATOR_PATTERN));
    }

    @Test
    void evaluate_DefaultProfileOnlyWarnsAppendixSamples() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("""
                        Appendix sample
                        key=value........................................
                        token=abcdef0123456789..........................
                        payload={"a":1,"b":2,"c":3}....................
                        sample_a=0000000000000000000000000000000000.....
                        sample_b=1111111111111111111111111111111111.....
                        sample_c=2222222222222222222222222222222222.....
                        sample_d=3333333333333333333333333333333333.....
                        sample_e=4444444444444444444444444444444444.....
                        """)
                .order(0)
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(LowSignalDecisionType.WARN, decision.decisionType());
        assertTrue(decision.reasonCodes().contains(LowSignalReasonCode.WARN_POSSIBLE_APPENDIX_SAMPLE_DATA));
    }

    @Test
    void evaluate_DefaultProfileMapsAppendixPayloadToSoftDeindexWithLegacyWarnCompatibility() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("""
                        Appendix sample
                        key=value........................................
                        token=abcdef0123456789..........................
                        payload={"a":1,"b":2,"c":3}....................
                        sample_a=0000000000000000000000000000000000.....
                        sample_b=1111111111111111111111111111111111.....
                        sample_c=2222222222222222222222222222222222.....
                        sample_d=3333333333333333333333333333333333.....
                        sample_e=4444444444444444444444444444444444.....
                        """)
                .order(0)
                .sectionPath(java.util.List.of("Appendix"))
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(RetrievalDisposition.SOFT_DEINDEX, decision.retrievalDisposition());
        assertTrue(decision.retrievalDispositionReasonCodes().contains(
                RetrievalDispositionReasonCode.APPENDIX_SAMPLE_PAYLOAD
        ));
        assertEquals(LowSignalDecisionType.WARN, decision.legacyDecisionType());
        assertTrue(decision.legacyReasonCodes().contains(LowSignalReasonCode.WARN_POSSIBLE_APPENDIX_SAMPLE_DATA));
    }

    @Test
    void evaluate_ProtectedTechnicalAnchor_UsesCanonicalProtect() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.CODE)
                .text("./gradlew test")
                .order(0)
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(RetrievalDisposition.PROTECT, decision.retrievalDisposition());
        assertTrue(decision.retrievalDispositionReasonCodes().contains(
                RetrievalDispositionReasonCode.PROTECTED_TECHNICAL_ANCHOR
        ));
        assertEquals(LowSignalDecisionType.PROTECT, decision.legacyDecisionType());
    }

    @Test
    void evaluate_DropAppendixSamplesProfile_UsesCanonicalDropAndLegacyDrop() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("""
                        Appendix sample
                        key=value........................................
                        token=abcdef0123456789..........................
                        payload={"a":1,"b":2,"c":3}....................
                        sample_a=0000000000000000000000000000000000.....
                        sample_b=1111111111111111111111111111111111.....
                        sample_c=2222222222222222222222222222222222.....
                        sample_d=3333333333333333333333333333333333.....
                        sample_e=4444444444444444444444444444444444.....
                        """)
                .order(0)
                .sectionPath(java.util.List.of("Appendix"))
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(true));

        assertEquals(RetrievalDisposition.DROP, decision.retrievalDisposition());
        assertEquals(LowSignalDecisionType.DROP, decision.legacyDecisionType());
    }

    @Test
    void build_InconsistentCanonicalAndLegacyDecision_ThrowsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> LowSignalBlockDecision.builder()
                .retrievalDisposition(RetrievalDisposition.DROP)
                .legacyDecisionType(LowSignalDecisionType.WARN)
                .build());

        assertTrue(exception.getMessage().contains("legacyDecisionType"));
    }

    @Test
    void evaluate_TocFragment_DropsAsNavigationArtifact() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("API Error Codes........15")
                .order(0)
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(RetrievalDisposition.DROP, decision.retrievalDisposition());
        assertTrue(decision.retrievalDispositionReasonCodes().contains(
                RetrievalDispositionReasonCode.TOC_NAVIGATION_ARTIFACT
        ));
    }

    @Test
    void evaluate_ChineseAppendixPayload_DefaultsToSoftDeindex() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("""
                        示例数据
                        token=abcdef0123456789
                        payload={"a":1,"b":2,"c":3}
                        value_a=0000000000000000000000000000000000
                        value_b=1111111111111111111111111111111111
                        value_c=2222222222222222222222222222222222
                        value_d=3333333333333333333333333333333333
                        value_e=4444444444444444444444444444444444
                        """)
                .order(0)
                .sectionPath(java.util.List.of("附录", "示例"))
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(RetrievalDisposition.SOFT_DEINDEX, decision.retrievalDisposition());
        assertTrue(decision.retrievalDispositionReasonCodes().contains(
                RetrievalDispositionReasonCode.APPENDIX_SAMPLE_PAYLOAD
        ));
    }

    @Test
    void evaluate_ErrorCodeAndCommand_StayProtectedAheadOfPayloadSignals() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("""
                        AUTH_001 invalid token
                        ./gradlew test
                        token=abcdef0123456789
                        payload={"a":1,"b":2}
                        """)
                .order(0)
                .sectionPath(java.util.List.of("附录", "Samples"))
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(RetrievalDisposition.PROTECT, decision.retrievalDisposition());
        assertTrue(decision.retrievalDispositionReasonCodes().contains(
                RetrievalDispositionReasonCode.PROTECTED_TECHNICAL_ANCHOR
        ));
    }

    @Test
    void evaluate_StructuralCorruption_DoesNotHardDropWithoutGarbageConfidence() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("Appendix sam ple ??? token maybe broken but still readable and explanatory.")
                .order(0)
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertNotEquals(RetrievalDisposition.DROP, decision.retrievalDisposition());
    }

    @Test
    void evaluate_PayloadSplitRoleWithinAppendixSection_DefaultsToSoftDeindex() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("""
                        token=abcdef0123456789
                        payload={"a":1,"b":2,"c":3}
                        value_y=8888888888888888888888888888888888
                        value_z=9999999999999999999999999999999999
                        value_aa=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                        value_ab=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
                        """)
                .order(0)
                .sectionPath(java.util.List.of("KB Guide", "附录", "Samples"))
                .metadata(Map.of("payloadSplitRole", "payload"))
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(RetrievalDisposition.SOFT_DEINDEX, decision.retrievalDisposition());
    }

    @Test
    void evaluate_HexHeavyAppendixPayload_DefaultsToSoftDeindex() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("""
                        token=abcdef0123456789
                        payload={"mode":"sample","retry":3}
                        value_k=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                        value_l=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
                        value_m=cccccccccccccccccccccccccccccccccccc
                        value_n=dddddddddddddddddddddddddddddddddddd
                        value_o=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
                        value_p=ffffffffffffffffffffffffffffffffffff
                        value_q=0000000000000000000000000000000000
                        value_r=1111111111111111111111111111111111
                        """)
                .order(0)
                .sectionPath(java.util.List.of("KB Guide", "附录", "Samples"))
                .metadata(Map.of())
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertEquals(RetrievalDisposition.SOFT_DEINDEX, decision.retrievalDisposition());
    }

    @Test
    void evaluate_ReferenceExamplesBody_DoesNotBecomeAppendixSample() {
        NormalizedBlock block = NormalizedBlock.builder()
                .type(NormalizedBlockType.PARAGRAPH)
                .text("""
                        This example shows how to call the API.
                        token=abcdef0123456789
                        payload={"question":"How do I retry?"}
                        status=200
                        body={"result":"ok"}
                        """)
                .order(0)
                .sectionPath(java.util.List.of("API Reference", "Examples"))
                .metadata(Map.of("payloadSplitRole", "payload"))
                .build();

        LowSignalBlockDecision decision = ruleEngine.evaluate(block, new DocumentPreprocessingProperties.ProfileProperties(false));

        assertNotEquals(RetrievalDisposition.SOFT_DEINDEX, decision.retrievalDisposition());
        assertTrue(decision.retrievalDispositionReasonCodes().stream()
                .noneMatch(reasonCode -> reasonCode == RetrievalDispositionReasonCode.APPENDIX_SAMPLE_PAYLOAD));
    }
}
