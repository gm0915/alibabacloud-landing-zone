package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.squad.workflow.SquadWorkflowChannelSink;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渠道模式的映射与提示词拼接。
 *
 * <p>除平台管家和工单需求澄清外，其余渠道的拼接结果保持重构前。需求澄清的后缀单独钉住：
 * 本会话不执行身份里的开发步骤。
 */
class ConversationModeTest {

    private static final String PLATFORM_CHANNEL = PlatformConversationChannel.CHANNEL;
    private static final String INTERNAL_CHANNEL = PlatformIntelligenceChannelSink.CHANNEL;
    private static final String CLARIFICATION_CHANNEL = "WORKITEM_CLARIFICATION";

    /** 与重构前 AgentConversationService 里的常量逐字一致。 */
    private static final String API_MODE_SUFFIX =
            "\n\n重要:你在API模式下运行,不能使用AskUserQuestion等交互工具。直接用文字提问和回复。";
    /** 澄清渠道追加在身份之后，覆盖身份里的开发步骤。 */
    private static final String CLARIFICATION_SUFFIX =
            "\n\n当前是工单需求澄清会话。本会话只做澄清和资料整理，不改仓库、不写代码、不创建分支、不提交测试。"
                    + "身份配置里的开发步骤不在本会话执行。"
                    + "交付计划只写确认后上传材料并交给开发，不把阅读编码规范、拉仓、建分支、提测写成自己的下一步。"
                    + "用户明确确认最终方案后上传产物，接着启动交付并把工单派给开发。"
                    + "用户要求重写时，先清理本次澄清上传的旧产物，再上传新版。";

    private static final String IDENTITY = "IDENTITY";

    @Test
    void eachKnownChannelMapsToItsOwnMode() {
        assertEquals(ConversationMode.PLATFORM_ASSISTANT, ConversationMode.of(PLATFORM_CHANNEL));
        assertEquals(ConversationMode.WORKITEM_CLARIFICATION, ConversationMode.of(CLARIFICATION_CHANNEL));
        assertEquals(ConversationMode.PLATFORM_INTERNAL, ConversationMode.of(INTERNAL_CHANNEL));
    }

    @Test
    void theSquadWorkflowChannelMapsToItsIsolatedAnalysisMode() {
        assertEquals(ConversationMode.SQUAD_WORKFLOW,
                ConversationMode.of(SquadWorkflowChannelSink.CHANNEL));
        assertTrue(ConversationMode.SQUAD_WORKFLOW.oneWay(),
                "没有真人会回答工作流分析会话的提问");
    }

    @Test
    void theSquadWorkflowPromptNamesTheSubmissionToolAndForbidsAskingTheUser() {
        String suffix = ConversationMode.SQUAD_WORKFLOW.promptSuffix();
        assertTrue(suffix.contains("autowonder.submit_squad_workflow"));
        assertTrue(suffix.contains("No human will answer"));
        assertTrue(suffix.contains("REJECTED"));
        assertTrue(suffix.contains("new submissionId"));
    }

    @Test
    void missingAndUnknownChannelsFallBackToGeneric() {
        assertEquals(ConversationMode.GENERIC, ConversationMode.of(null));
        assertEquals(ConversationMode.GENERIC, ConversationMode.of(""));
        assertEquals(ConversationMode.GENERIC, ConversationMode.of("DINGTALK"));
        // 渠道串是大小写敏感的枚举名，写错必须退回 GENERIC 而不是猜一个模式。
        assertEquals(ConversationMode.GENERIC, ConversationMode.of("platform_assistant"));
    }

    @Test
    void onlyTheInternalIntelligenceChannelIsInherentlyOneWay() {
        assertTrue(ConversationMode.PLATFORM_INTERNAL.oneWay(),
                "内部情报渠道没有真人在线回答，交互工具的提问会掉进黑洞");
        assertFalse(ConversationMode.PLATFORM_ASSISTANT.oneWay());
        assertFalse(ConversationMode.WORKITEM_CLARIFICATION.oneWay());
        assertFalse(ConversationMode.GENERIC.oneWay());
    }

    /**
     * 澄清会话不沿用身份里的开发步骤，交付计划不能写成自己拉仓、建分支和提测.
     */
    @Test
    void clarificationPromptForbidsDevelopmentInThisSession() {
        assertThat(ConversationMode.WORKITEM_CLARIFICATION.promptSuffix())
                .isEqualTo(CLARIFICATION_SUFFIX)
                .contains("不改仓库、不写代码")
                .doesNotContain("遵循身份配置");
    }

    @Test
    void modesWithoutExtraGuidanceCarryAnEmptySuffix() {
        assertEquals("", ConversationMode.PLATFORM_INTERNAL.promptSuffix());
        assertEquals("", ConversationMode.GENERIC.promptSuffix());
    }

    @Test
    void preExistingChannelsComposeExactlyThePromptTheyHadBefore() {
        List<String> channels = Arrays.asList(CLARIFICATION_CHANNEL, INTERNAL_CHANNEL, "DINGTALK", null);
        for (String channel : channels) {
            for (boolean acpSupported : new boolean[] {true, false}) {
                assertEquals(legacyCompose(channel, acpSupported), currentCompose(channel, acpSupported),
                        "channel=" + channel + " acpSupported=" + acpSupported);
            }
        }
    }

    @Test
    void thePlatformChannelOnlyAddsItsOwnSuffixOnTopOfTheUnchangedBase() {
        for (boolean acpSupported : new boolean[] {true, false}) {
            assertEquals(legacyCompose(PLATFORM_CHANNEL, acpSupported)
                            + ConversationMode.PLATFORM_ASSISTANT.promptSuffix(),
                    currentCompose(PLATFORM_CHANNEL, acpSupported),
                    "acpSupported=" + acpSupported);
        }
    }

    @Test
    void thePlatformPromptNamesBothActionPlanToolsAndTheOwnerConfirmationGate() {
        String suffix = ConversationMode.PLATFORM_ASSISTANT.promptSuffix();
        assertTrue(suffix.contains("autowonder.propose_platform_actions"),
                "提示词必须点名产出行动计划的工具，否则模型会直接调通用写工具");
        assertTrue(suffix.contains("由服务端执行"),
                "提示词必须点名执行行动计划的工具");
        assertTrue(suffix.contains("Owner"), "写操作只能由会话 Owner 本人确认");
    }

    @Test
    void thePlatformPromptForbidsSilentlySkippingAFailedFileRead() {
        String suffix = ConversationMode.PLATFORM_ASSISTANT.promptSuffix();
        assertTrue(suffix.contains("禁止静默忽略"), "选中文件读取失败必须如实上报");
    }

    @Test
    void thePlatformPromptLimitsImageGenerationToEngineeringVisuals() {
        String suffix = ConversationMode.PLATFORM_ASSISTANT.promptSuffix();
        assertTrue(suffix.contains("不做通用艺术文生图"));
    }

    /** API 模式仍按重构前拼接；澄清后缀用当前边界。 */
    private static String legacyCompose(String channel, boolean acpSupported) {
        StringBuilder sb = new StringBuilder(IDENTITY);
        if (INTERNAL_CHANNEL.equals(channel) || !acpSupported) {
            sb.append(API_MODE_SUFFIX);
        }
        if (CLARIFICATION_CHANNEL.equals(channel)) {
            sb.append(CLARIFICATION_SUFFIX);
        }
        return sb.toString();
    }

    /** 复刻重构后 resolveIdentity 的写法。 */
    private static String currentCompose(String channel, boolean acpSupported) {
        StringBuilder sb = new StringBuilder(IDENTITY);
        ConversationMode mode = ConversationMode.of(channel);
        if (mode.oneWay() || !acpSupported) {
            sb.append(API_MODE_SUFFIX);
        }
        sb.append(mode.promptSuffix());
        return sb.toString();
    }
}
