package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.runtime.StopReason;
import io.mosire.agentlib.runtime.TurnResult;
import io.mosire.agentlib.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** D-020：默认预算为 128 轮/5 分钟，且预算耗尽回执说明真实进度。 */
class SecretarySessionBudgetTest {

    @Test
    void defaultsAre128RoundsAndFiveMinutesWithEightToolCalls() {
        assertThat(AgentRunner.DEFAULT_MAX_ROUNDS).isEqualTo(128);
        assertThat(AgentRunner.DEFAULT_TIME_BUDGET).isEqualTo(Duration.ofMinutes(5));
        assertThat(AgentRunner.DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE).isEqualTo(8);
    }

    @Test
    void stoppedReplyWithoutProgressSaysNothingWasCreatedOrSubmitted() {
        AgentRunResult result = new AgentRunResult(
                new TurnResult(StopReason.TURN_LIMIT, 128, 0, null), List.of());

        String text = SecretarySession.stoppedReply(result);

        assertThat(text)
                .contains("行动草案：未创建")
                .contains("没有提交行动或查询")
                .contains("世界状态：未改变")
                .contains("再发一次");
    }

    @Test
    void stoppedReplyWithActionDraftReportsDraftAndAsksToContinue() {
        ToolInvocation submit = new ToolInvocation("call-1", SecretaryTicketTools.SUBMIT,
                Map.of("type", "ACTION_DRAFT", "title", "行动",
                        "body", "正文", "idempotencyKey", "key"),
                ToolResult.ok("工单已提交"));
        AgentRunResult result = new AgentRunResult(
                new TurnResult(StopReason.TIME_BUDGET, 128, 1, null), List.of(submit));

        String text = SecretarySession.stoppedReply(result);

        assertThat(text)
                .contains("行动草案：已创建，尚未确认，不会自动执行")
                .contains("不需要重新描述整段内容")
                .contains("世界状态：未改变");
    }
}
