package com.aliyun.autowonder.conversation;

/**
 * 渠道决定 Agent 在一轮对话里的行为边界。抽成枚举是为了让「哪个渠道追加哪段提示」
 * 有唯一出处：新增渠道时必须显式选一种模式，不会悄悄沿用别人的边界。
 */
public enum ConversationMode {

    /**
     * 平台管家对话。读取类操作直接做；任何会改动平台数据的操作都必须先产出参数冻结的
     * 行动计划，等会话 Owner 本人显式确认后才能执行，禁止边解释边动手。
     */
    PLATFORM_ASSISTANT(
            "\n\n当前是平台管家对话，与你交谈的就是这个会话的唯一 Owner。"
                    + "查询、读取、汇总类请求直接完成，不要反复确认。"
                    + "任何会创建、修改、删除平台数据的操作，必须先调用"
                    + " autowonder.propose_platform_actions 产出参数冻结的一次性行动计划，"
                    + "把目标、参数、影响面和执行步骤讲清楚，等待 Owner 在平台界面显式确认，由服务端执行，禁止调用"
                    + "任何直接写入工具；未确认前不得执行任何写操作，"
                    + "也不得自行放宽或替换计划里的参数。"
                    + "Owner 选中的文件读取失败时必须如实报告，禁止静默忽略后继续作答。"
                    + "当前项目是会话绑定的唯一工作空间，禁止操作其他项目。"
                    + "用可公开的简短分析摘要说明进度和工具目的，不输出内部思维链。"
                    + "优先调用现有工具，按需读取详情，不加载完整仓库或无关历史。"
                    + "只做工程可视化配图，不做通用艺术文生图。",
            false),

    /**
     * 工单需求澄清。身份里的开发步骤不在本会话执行。
     * 澄清题只确定范围；确认方案和决定启动分成两步，计划时间写在同一次指派上。
     */
    WORKITEM_CLARIFICATION(
            "\n\n当前是工单需求澄清会话。本会话只做澄清和资料整理，不改仓库、不写代码、不创建分支、不提交测试。"
                    + "身份配置里的开发步骤不在本会话执行。"
                    + "交付计划只写确认后上传材料并交给开发，不把阅读编码规范、拉仓、建分支、提测写成自己的下一步。"
                    + "澄清题的回答只确定范围，不是对最终方案的确认，也不是启动交付的授权。"
                    + "题目答完后，先把整理后的方案发给用户，停下来等用户明确确认这份方案。未确认前不上传产物、不指派、不启动交付。"
                    + "用户明确确认方案后，再上传产物。上传完成后，单独询问是否启动以及何时启动：立刻启动、在用户给出的时间启动，或不启动。"
                    + "只有用户选定启动后，才调用一次 assign_workitem 把工单派给开发。用户指定未来时间时，在这一次调用里传入 scheduledStartAt，不要先指派再补一次定时。用户选择不启动时不要指派。"
                    + "用户要求重写时，先清理本次澄清上传的旧产物，再上传新版。",
            false),

    /**
     * 小队工作流分析：没有真人会回答，只能经任务专用工具提交候选图并接收结构化修复反馈。
     * 不允许提问、行动计划或任何通用写工具。
     */
    SQUAD_WORKFLOW(
            "\n\nThis is an isolated squad-workflow analysis task. No human will answer questions. "
                    + "Read the complete INPUT, analyze the squad once, self-review the result against "
                    + "the seven checks, and submit it with autowonder.submit_squad_workflow. "
                    + "When the tool returns REJECTED, fix the reported fields and resubmit with a new "
                    + "submissionId; never ask the user and never call any other write tool.",
            true),

    WORKITEM_PRD("\n\n当前为 PRD 设计会话。仅生成设计，不执行交付或修改仓库。需要澄清时用文字回复，不调用交互工具。", true),

    /** 内部情报渠道：没有真人在线回答，交互工具的提问会掉进黑洞。 */
    PLATFORM_INTERNAL("", true),

    /** 其余渠道：能否交互取决于执行器是否声明了 ACP 能力。 */
    GENERIC("", false);

    private final String promptSuffix;
    private final boolean oneWay;

    ConversationMode(String promptSuffix, boolean oneWay) {
        this.promptSuffix = promptSuffix;
        this.oneWay = oneWay;
    }

    public static ConversationMode of(String channel) {
        if ("WORKITEM_PRD".equals(channel)) return WORKITEM_PRD;
        if (channel == null) {
            return GENERIC;
        }
        if (PlatformConversationChannel.CHANNEL.equals(channel)) {
            return PLATFORM_ASSISTANT;
        }
        if ("WORKITEM_CLARIFICATION".equals(channel)) {
            return WORKITEM_CLARIFICATION;
        }
        if (com.aliyun.autowonder.squad.workflow.SquadWorkflowChannelSink.CHANNEL.equals(channel)) {
            return SQUAD_WORKFLOW;
        }
        if (PlatformIntelligenceChannelSink.CHANNEL.equals(channel)) {
            return PLATFORM_INTERNAL;
        }
        return GENERIC;
    }

    /** 渠道本身就注定单向，与执行器能力无关。 */
    public boolean oneWay() {
        return oneWay;
    }

    public String promptSuffix() {
        return promptSuffix;
    }

    public String applyToIdentity(String identity, boolean supportsAcpInteraction) {
        return identity + (oneWay || !supportsAcpInteraction
                ? "\n\n重要:你在API模式下运行,不能使用AskUserQuestion等交互工具。直接用文字提问和回复。" : "")
                + promptSuffix;
    }
}
