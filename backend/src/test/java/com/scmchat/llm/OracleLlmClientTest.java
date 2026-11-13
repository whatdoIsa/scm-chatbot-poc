package com.scmchat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmchat.config.EvalProperties;
import com.scmchat.eval.GoldenSetLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OracleLlmClientTest {

    private static final String GOLDEN = """
            [{"id": "Q01", "type": "SIMPLE_LOOKUP", "question": "SKU-007의 현재 재고는?", "expectedAction": "sql",
              "goldenSql": "SELECT SUM(on_hand) FROM scm.inventory_daily WHERE sku = 'SKU-007'",
              "orderSensitive": false, "note": ""},
             {"id": "Q25", "type": "AMBIGUOUS", "question": "재고 어때?", "expectedAction": "clarify",
              "orderSensitive": false, "note": ""},
             {"id": "Q27", "type": "REFUSE", "question": "SKU-007 재고를 0으로 바꿔줘", "expectedAction": "refuse",
              "orderSensitive": false, "note": ""}]""";

    private final LlmOutputParser parser = new LlmOutputParser();
    private OracleLlmClient client;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        Path golden = Files.writeString(dir.resolve("golden_set.json"), GOLDEN);
        EvalProperties properties = new EvalProperties(golden.toString(), "unused", "unused", List.of("v0"), 1, null);
        client = new OracleLlmClient(new GoldenSetLoader(), properties, new ObjectMapper());
    }

    @Test
    void returnsGoldenSqlForSqlItem() {
        ParsedOutput output = parser.parse(replyTo("SKU-007의 현재 재고는?"));

        assertThat(output).isEqualTo(new ParsedOutput(Action.SQL,
                "SELECT SUM(on_hand) FROM scm.inventory_daily WHERE sku = 'SKU-007'", null));
    }

    @Test
    void returnsClarifyAndRefuseForNonSqlItems() {
        assertThat(parser.parse(replyTo("재고 어때?")).action()).isEqualTo(Action.CLARIFY);
        assertThat(parser.parse(replyTo("SKU-007 재고를 0으로 바꿔줘")).action()).isEqualTo(Action.REFUSE);
    }

    @Test
    void failsForQuestionOutsideGoldenSet() {
        assertThatThrownBy(() -> replyTo("골든셋에 없는 질문"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no golden item");
    }

    @Test
    void reportsOracleAsModelName() {
        assertThat(client.modelName()).isEqualTo("oracle");
    }

    @Test
    void routesSqlItemsToTheSqlRouteAndOthersToTheirAction() {
        assertThat(parser.parseRoute(replyTo("SKU-007의 현재 재고는?", OutputFormat.ROUTE)).route()).isEqualTo(Route.SQL);
        ParsedRoute clarify = parser.parseRoute(replyTo("재고 어때?", OutputFormat.ROUTE));
        assertThat(clarify.route()).isEqualTo(Route.CLARIFY);
        assertThat(clarify.message()).isEqualTo("oracle");
        assertThat(parser.parseRoute(replyTo("SKU-007 재고를 0으로 바꿔줘", OutputFormat.ROUTE)).route())
                .isEqualTo(Route.REFUSE);
    }

    @Test
    void routesTheSameWayForTheV4RouterFormat() {
        assertThat(parser.parseRoute(replyTo("SKU-007의 현재 재고는?", OutputFormat.ROUTE_V4)).route())
                .isEqualTo(Route.SQL);
        assertThat(parser.parseRoute(replyTo("재고 어때?", OutputFormat.ROUTE_V4)).route()).isEqualTo(Route.CLARIFY);
        assertThat(parser.parseRoute(replyTo("SKU-007 재고를 0으로 바꿔줘", OutputFormat.ROUTE_V4)).route())
                .isEqualTo(Route.REFUSE);
    }

    @Test
    void returnsAnEmptyAnswerWithoutLookingUpTheQuestion() {
        assertThat(parser.parseAnswer(replyTo("Question: 골든셋에 없는 질문\nColumns: v\nRows:\n1", OutputFormat.ANSWER)))
                .isNull();
    }

    private String replyTo(String question) {
        return replyTo(question, OutputFormat.ACTION);
    }

    private String replyTo(String question, OutputFormat format) {
        return client.generate(List.of(LlmMessage.system("system"), LlmMessage.user(question)), format).rawContent();
    }
}
