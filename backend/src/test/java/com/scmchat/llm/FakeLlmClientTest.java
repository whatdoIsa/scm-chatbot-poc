package com.scmchat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FakeLlmClientTest {

    private static final List<LlmMessage> MESSAGES = List.of(LlmMessage.user("재고 어때?"));

    private final FakeLlmClient client = new FakeLlmClient();

    @Test
    void returnsDefaultClarifyReplyWhenQueueIsEmpty() {
        LlmReply reply = client.generate(MESSAGES, OutputFormat.ACTION);

        assertThat(reply).isEqualTo(new LlmReply("{\"action\":\"clarify\",\"sql\":\"\",\"message\":\"fake\"}", 0, 0));
        assertThat(new LlmOutputParser().parse(reply.rawContent()))
                .isEqualTo(new ParsedOutput(Action.CLARIFY, null, "fake"));
    }

    @Test
    void returnsQueuedRepliesInOrderThenFallsBackToDefault() {
        client.enqueue("first", "second");

        assertThat(client.generate(MESSAGES, OutputFormat.ACTION).rawContent()).isEqualTo("first");
        assertThat(client.generate(MESSAGES, OutputFormat.ACTION).rawContent()).isEqualTo("second");
        assertThat(client.generate(MESSAGES, OutputFormat.ACTION).rawContent()).isEqualTo(FakeLlmClient.DEFAULT_REPLY);
    }

    @Test
    void reportsFakeAsModelName() {
        assertThat(client.modelName()).isEqualTo("fake");
    }

    @Test
    void defaultReplyFollowsTheRequestedFormat() {
        LlmOutputParser parser = new LlmOutputParser();

        assertThat(parser.parseRoute(client.generate(MESSAGES, OutputFormat.ROUTE).rawContent()).route())
                .isEqualTo(Route.CLARIFY);
        assertThat(parser.parseRoute(client.generate(MESSAGES, OutputFormat.ROUTE_V4).rawContent()).route())
                .isEqualTo(Route.CLARIFY);
        assertThat(parser.parseAnswer(client.generate(MESSAGES, OutputFormat.ANSWER).rawContent())).isNull();
    }

    @Test
    void queuedReplyIsReturnedWhateverTheFormat() {
        client.enqueue("queued");

        assertThat(client.generate(MESSAGES, OutputFormat.ROUTE).rawContent()).isEqualTo("queued");
    }
}
