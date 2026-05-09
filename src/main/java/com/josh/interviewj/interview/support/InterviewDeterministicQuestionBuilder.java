package com.josh.interviewj.interview.support;

import com.josh.interviewj.common.enums.ContentLocale;
import com.josh.interviewj.interview.model.InterviewQuestion;
import com.josh.interviewj.interview.support.InterviewQuestionBlueprintFactory.QuestionBlueprint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds deterministic interview questions from blueprints.
 *
 * <p>Used as fallback when LLM generation fails or is not available.
 * Generates language-appropriate questions based on content locale.</p>
 */
@Component
public class InterviewDeterministicQuestionBuilder {

    private final InterviewQuestionBlueprintFactory blueprintFactory;

    public InterviewDeterministicQuestionBuilder(InterviewQuestionBlueprintFactory blueprintFactory) {
        this.blueprintFactory = blueprintFactory;
    }

    /**
     * Build deterministic questions for a session.
     *
     * @param sessionId session ID
     * @param jobTitle job title
     * @param difficultyLevel difficulty level
     * @param contentLocale content locale for language
     * @return list of deterministic questions
     */
    public List<InterviewQuestion> buildQuestions(
            Long sessionId,
            String jobTitle,
            String jobDescription,
            String difficultyLevel,
            String contentLocale
    ) {
        List<QuestionBlueprint> blueprints = blueprintFactory.createBlueprints(difficultyLevel);
        List<InterviewQuestion> questions = new ArrayList<>();

        String topic = (jobTitle == null || jobTitle.isBlank()) ? "this role" : jobTitle;
        String descriptionHint = summarizeJobDescription(jobDescription);
        ContentLocale locale = ContentLocale.resolveOrDefault(contentLocale);
        boolean isChinese = locale == ContentLocale.ZH_CN;

        for (QuestionBlueprint blueprint : blueprints) {
            String questionContent = buildQuestionContent(blueprint, topic, descriptionHint, isChinese);

            questions.add(InterviewQuestion.builder()
                    .sessionId(sessionId)
                    .externalId(java.util.UUID.randomUUID())
                    .questionType(blueprint.questionType())
                    .questionContent(questionContent)
                    .difficulty(blueprint.difficulty())
                    .estimatedMinutes(blueprint.estimatedMinutes())
                    .sequenceNumber(blueprint.sequenceNumber())
                    .questionKind(blueprint.questionKind())
                    .branchDepth(0)
                    .build());
        }

        return questions;
    }

    public String buildFollowUpQuestion(
            InterviewQuestion parentQuestion,
            com.josh.interviewj.interview.model.InterviewFollowUpIntent followUpIntent,
            int branchDepth,
            String contentLocale
    ) {
        boolean isChinese = ContentLocale.resolveOrDefault(contentLocale) == ContentLocale.ZH_CN;
        if (followUpIntent == com.josh.interviewj.interview.model.InterviewFollowUpIntent.CLARIFY) {
            return isChinese
                    ? "请你结合刚才的回答，进一步澄清具体步骤、关键判断和实际结果。"
                    : "Please clarify the concrete steps, key decisions, and actual outcome behind your previous answer.";
        }
        return isChinese
                ? "请在刚才回答的基础上继续深入，重点展开你的技术判断、权衡过程和落地细节。"
                : "Please go deeper on your previous answer, especially the technical judgment, trade-offs, and implementation details.";
    }

    private String buildQuestionContent(QuestionBlueprint blueprint, String topic, String descriptionHint, boolean isChinese) {
        return switch (blueprint.questionType()) {
            case "EXPERIENCE" -> isChinese
                    ? buildChineseExperienceQuestion(blueprint, topic, descriptionHint)
                    : buildEnglishExperienceQuestion(blueprint, topic, descriptionHint);
            case "SKILL" -> isChinese
                    ? buildChineseSkillQuestion(blueprint, topic, descriptionHint)
                    : buildEnglishSkillQuestion(blueprint, topic, descriptionHint);
            case "SCENARIO" -> isChinese
                    ? buildChineseScenarioQuestion(blueprint, topic, descriptionHint)
                    : buildEnglishScenarioQuestion(blueprint, topic, descriptionHint);
            case "BEHAVIORAL" -> isChinese
                    ? buildChineseBehavioralQuestion(blueprint, topic, descriptionHint)
                    : buildEnglishBehavioralQuestion(blueprint, topic, descriptionHint);
            default -> isChinese
                    ? "请介绍一下你在 " + topic + " 方面的相关经验。"
                    : "Describe your experience with " + topic + ".";
        };
    }

    private String buildEnglishExperienceQuestion(QuestionBlueprint blueprint, String topic, String descriptionHint) {
        return switch (blueprint.sequenceNumber()) {
            case 1 -> "For the " + topic + " role, please introduce yourself through one concrete achievement you're proud of" + descriptionSuffix(descriptionHint) + ".";
            case 7 -> "For " + topic + ", describe a project where you demonstrated ownership and drove measurable impact" + descriptionSuffix(descriptionHint) + ".";
            case 11 -> "For " + topic + ", share an example of how you influenced others or led a cross-team initiative" + descriptionSuffix(descriptionHint) + ".";
            default -> "For " + topic + ", tell me about a representative project from your experience" + descriptionSuffix(descriptionHint) + ".";
        };
    }

    private String buildChineseExperienceQuestion(QuestionBlueprint blueprint, String topic, String descriptionHint) {
        return switch (blueprint.sequenceNumber()) {
            case 1 -> "针对 " + topic + " 岗位，请通过一个具体的成就来介绍一下你自己" + chineseDescriptionSuffix(descriptionHint) + "。";
            case 7 -> "针对 " + topic + "，请描述一个你展现主人翁意识并产生可衡量影响的项目" + chineseDescriptionSuffix(descriptionHint) + "。";
            case 11 -> "针对 " + topic + "，请分享一个你如何影响他人或主导跨团队协作的例子" + chineseDescriptionSuffix(descriptionHint) + "。";
            default -> "针对 " + topic + "，请介绍一下你经历过的代表性项目" + chineseDescriptionSuffix(descriptionHint) + "。";
        };
    }

    private String buildEnglishSkillQuestion(QuestionBlueprint blueprint, String topic, String descriptionHint) {
        return switch (blueprint.sequenceNumber()) {
            case 2 -> "For " + topic + ", explain a core technical concept you consider essential for this role" + descriptionSuffix(descriptionHint) + ".";
            case 3 -> "For " + topic + ", describe a challenging technical problem you solved. Walk me through your approach" + descriptionSuffix(descriptionHint) + ".";
            case 10 -> "For " + topic + ", how do you ensure code quality, performance, or security in your work" + descriptionSuffix(descriptionHint) + ".";
            default -> "For " + topic + ", tell me about a technical skill you've developed" + descriptionSuffix(descriptionHint) + ".";
        };
    }

    private String buildChineseSkillQuestion(QuestionBlueprint blueprint, String topic, String descriptionHint) {
        return switch (blueprint.sequenceNumber()) {
            case 2 -> "针对 " + topic + "，请解释一个你认为对这个岗位至关重要的核心技术概念" + chineseDescriptionSuffix(descriptionHint) + "。";
            case 3 -> "针对 " + topic + "，请描述一个你解决过的具有挑战性的技术问题，并讲解你的思路" + chineseDescriptionSuffix(descriptionHint) + "。";
            case 10 -> "针对 " + topic + "，你在工作中如何保证代码质量、性能或安全性" + chineseDescriptionSuffix(descriptionHint) + "？";
            default -> "针对 " + topic + "，请介绍你掌握的一项技术技能" + chineseDescriptionSuffix(descriptionHint) + "。";
        };
    }

    private String buildEnglishScenarioQuestion(QuestionBlueprint blueprint, String topic, String descriptionHint) {
        return switch (blueprint.sequenceNumber()) {
            case 4 -> "For " + topic + ", imagine you receive an alert about a production issue. Walk me through your diagnosis process" + descriptionSuffix(descriptionHint) + ".";
            case 5 -> "For " + topic + ", describe a technical trade-off you had to make. What factors did you consider" + descriptionSuffix(descriptionHint) + "?";
            case 9 -> "For " + topic + ", how would you approach designing a system for a requirement like " + descriptionHint + "?";
            case 12 -> "For " + topic + ", describe how you would evolve an existing architecture to handle new requirements or scale" + descriptionSuffix(descriptionHint) + ".";
            default -> "For " + topic + ", describe how you would handle a challenging scenario" + descriptionSuffix(descriptionHint) + ".";
        };
    }

    private String buildChineseScenarioQuestion(QuestionBlueprint blueprint, String topic, String descriptionHint) {
        return switch (blueprint.sequenceNumber()) {
            case 4 -> "针对 " + topic + "，假设你收到一个生产环境告警，请描述你的排查过程" + chineseDescriptionSuffix(descriptionHint) + "。";
            case 5 -> "针对 " + topic + "，请描述一个你做过的技术取舍，你考虑了哪些因素" + chineseDescriptionSuffix(descriptionHint) + "？";
            case 9 -> "针对 " + topic + "，如果让你设计一个系统来满足类似“" + descriptionHint + "”这样的需求，请描述你的设计思路。";
            case 12 -> "针对 " + topic + "，请描述你如何演进现有架构以应对新需求或扩展" + chineseDescriptionSuffix(descriptionHint) + "。";
            default -> "针对 " + topic + "，请描述你如何处理一个具有挑战性的场景" + chineseDescriptionSuffix(descriptionHint) + "。";
        };
    }

    private String buildEnglishBehavioralQuestion(QuestionBlueprint blueprint, String topic, String descriptionHint) {
        return switch (blueprint.sequenceNumber()) {
            case 6 -> "For " + topic + ", describe a disagreement you had with a colleague. How did you resolve it" + descriptionSuffix(descriptionHint) + "?";
            case 8 -> "For " + topic + ", tell me about a time you received difficult feedback. How did you respond" + descriptionSuffix(descriptionHint) + "?";
            default -> "For " + topic + ", describe a challenging interpersonal situation you navigated" + descriptionSuffix(descriptionHint) + ".";
        };
    }

    private String buildChineseBehavioralQuestion(QuestionBlueprint blueprint, String topic, String descriptionHint) {
        return switch (blueprint.sequenceNumber()) {
            case 6 -> "针对 " + topic + "，请描述一次你与同事的分歧，你是如何解决的" + chineseDescriptionSuffix(descriptionHint) + "？";
            case 8 -> "针对 " + topic + "，请分享一次你收到困难反馈的经历，你是如何应对的" + chineseDescriptionSuffix(descriptionHint) + "？";
            default -> "针对 " + topic + "，请描述一个你处理过的人际挑战" + chineseDescriptionSuffix(descriptionHint) + "。";
        };
    }

    private String summarizeJobDescription(String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            return "";
        }
        String normalized = jobDescription.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40).trim() + "...";
    }

    private String descriptionSuffix(String descriptionHint) {
        return descriptionHint == null || descriptionHint.isBlank()
                ? ""
                : ", especially around " + descriptionHint;
    }

    private String chineseDescriptionSuffix(String descriptionHint) {
        return descriptionHint == null || descriptionHint.isBlank()
                ? ""
                : "，并尽量结合岗位描述中提到的“" + descriptionHint + "”";
    }
}
