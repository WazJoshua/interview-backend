package com.josh.interviewj.interview.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewQuestionBlueprintFactoryTest {

    private final InterviewQuestionBlueprintFactory factory = new InterviewQuestionBlueprintFactory();

    @Test
    void createBlueprints_JuniorLevel_Returns8Questions() {
        List<InterviewQuestionBlueprintFactory.QuestionBlueprint> blueprints = factory.createBlueprints("JUNIOR");

        assertEquals(8, blueprints.size());
        assertEquals(8, factory.getExpectedQuestionCount("JUNIOR"));

        // Verify sequence numbers are correct
        for (int i = 0; i < blueprints.size(); i++) {
            assertEquals(i + 1, blueprints.get(i).sequenceNumber());
        }
    }

    @Test
    void createBlueprints_MidLevel_Returns10Questions() {
        List<InterviewQuestionBlueprintFactory.QuestionBlueprint> blueprints = factory.createBlueprints("MID");

        assertEquals(10, blueprints.size());
        assertEquals(10, factory.getExpectedQuestionCount("MID"));
    }

    @Test
    void createBlueprints_SeniorLevel_Returns12Questions() {
        List<InterviewQuestionBlueprintFactory.QuestionBlueprint> blueprints = factory.createBlueprints("SENIOR");

        assertEquals(12, blueprints.size());
        assertEquals(12, factory.getExpectedQuestionCount("SENIOR"));
    }

    @Test
    void createBlueprints_NullLevel_DefaultsToMid() {
        List<InterviewQuestionBlueprintFactory.QuestionBlueprint> blueprints = factory.createBlueprints(null);

        assertEquals(10, blueprints.size());
    }

    @Test
    void createBlueprints_EmptyLevel_DefaultsToMid() {
        List<InterviewQuestionBlueprintFactory.QuestionBlueprint> blueprints = factory.createBlueprints("");

        assertEquals(10, blueprints.size());
    }

    @Test
    void createBlueprints_LowerCaseLevel_NormalizesToUpperCase() {
        List<InterviewQuestionBlueprintFactory.QuestionBlueprint> blueprints = factory.createBlueprints("junior");

        assertEquals(8, blueprints.size());
    }

    @Test
    void createBlueprints_AllBlueprintsHaveRequiredFields() {
        List<InterviewQuestionBlueprintFactory.QuestionBlueprint> blueprints = factory.createBlueprints("SENIOR");

        for (InterviewQuestionBlueprintFactory.QuestionBlueprint blueprint : blueprints) {
            assertNotNull(blueprint.questionType());
            assertNotNull(blueprint.questionKind());
            assertNotNull(blueprint.questionGoal());
            assertTrue(blueprint.difficulty() >= 1 && blueprint.difficulty() <= 5);
            assertTrue(blueprint.estimatedMinutes() >= 5 && blueprint.estimatedMinutes() <= 15);
        }
    }

    @Test
    void createBlueprints_FirstBlueprint_IsExperience() {
        InterviewQuestionBlueprintFactory.QuestionBlueprint first = factory.createBlueprints("JUNIOR").get(0);

        assertEquals("EXPERIENCE", first.questionType());
        assertEquals(1, first.sequenceNumber());
    }
}