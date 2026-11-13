package com.scmchat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scmchat.tool.RawToolArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LlmOutputParserTest {

    private final LlmOutputParser parser = new LlmOutputParser();

    @Test
    void parsesSqlAction() {
        ParsedOutput output = parser.parse(
                "{\"action\":\"sql\",\"sql\":\"SELECT on_hand FROM inventory_daily\",\"message\":\"\"}");

        assertThat(output).isEqualTo(new ParsedOutput(Action.SQL, "SELECT on_hand FROM inventory_daily", null));
    }

    @Test
    void parsesClarifyAction() {
        ParsedOutput output = parser.parse(
                "{\"action\":\"clarify\",\"sql\":\"\",\"message\":\"어느 품목의 재고를 확인할까요?\"}");

        assertThat(output).isEqualTo(new ParsedOutput(Action.CLARIFY, null, "어느 품목의 재고를 확인할까요?"));
    }

    @Test
    void parsesRefuseAction() {
        ParsedOutput output = parser.parse(
                "{\"action\":\"refuse\",\"sql\":\"\",\"message\":\"데이터를 변경할 수 없습니다.\"}");

        assertThat(output).isEqualTo(new ParsedOutput(Action.REFUSE, null, "데이터를 변경할 수 없습니다."));
    }

    @Test
    void stripsJsonCodeFenceAndSurroundingWhitespace() {
        ParsedOutput output = parser.parse(
                "\n  ```json\n{\"action\":\"sql\",\"sql\":\"SELECT 1\",\"message\":\"\"}\n```  \n");

        assertThat(output.sql()).isEqualTo("SELECT 1");
    }

    @Test
    void stripsCodeFenceWithoutLanguage() {
        ParsedOutput output = parser.parse("```\n{\"action\":\"sql\",\"sql\":\"SELECT 1\",\"message\":\"\"}\n```");

        assertThat(output.action()).isEqualTo(Action.SQL);
    }

    @Test
    void removesTrailingSemicolonFromSql() {
        ParsedOutput output = parser.parse("{\"action\":\"sql\",\"sql\":\"SELECT 1 ; \\n\",\"message\":\"\"}");

        assertThat(output.sql()).isEqualTo("SELECT 1");
    }

    @Test
    void keepsSemicolonInsideSqlLiteral() {
        ParsedOutput output = parser.parse(
                "{\"action\":\"sql\",\"sql\":\"SELECT ';' AS sep;\",\"message\":\"\"}");

        assertThat(output.sql()).isEqualTo("SELECT ';' AS sep");
    }

    @Test
    void treatsMissingAndNullFieldsAsNull() {
        ParsedOutput output = parser.parse("{\"action\":\"clarify\",\"sql\":null,\"message\":\"기간을 알려 주세요.\"}");

        assertThat(output.sql()).isNull();
        assertThat(parser.parse("{\"action\":\"sql\",\"sql\":\"SELECT 1\"}").message()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"action\":\"sql\",\"sql\":\"\",\"message\":\"\"}",
            "{\"action\":\"sql\",\"sql\":\"   \",\"message\":\"조회합니다\"}",
            "{\"action\":\"sql\",\"sql\":\";\",\"message\":\"\"}",
            "{\"action\":\"sql\",\"message\":\"\"}"
    })
    void rejectsSqlActionWithoutSql(String raw) {
        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(FormatException.class)
                .hasMessageContaining("sql is empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"action\":\"clarify\",\"sql\":\"\",\"message\":\"\"}",
            "{\"action\":\"refuse\",\"sql\":\"\",\"message\":\"  \"}",
            "{\"action\":\"refuse\",\"sql\":\"SELECT 1\"}"
    })
    void rejectsClarifyOrRefuseWithoutMessage(String raw) {
        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(FormatException.class)
                .hasMessageContaining("message is empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"action\":\"delete\",\"sql\":\"\",\"message\":\"x\"}",
            "{\"action\":\"SQL\",\"sql\":\"SELECT 1\",\"message\":\"\"}",
            "{\"action\":\"\",\"sql\":\"SELECT 1\",\"message\":\"\"}",
            "{\"sql\":\"SELECT 1\",\"message\":\"\"}"
    })
    void rejectsUnknownAction(String raw) {
        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(FormatException.class)
                .hasMessageContaining("unknown action");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Here is the answer: {\"action\":\"sql\",\"sql\":\"SELECT 1\",\"message\":\"\"}",
            "{\"action\":\"sql\",\"sql\":\"SELECT 1\",\"message\":\"\"} Hope this helps.",
            "{\"action\":\"sql\",\"sql\":\"SELECT 1\",\"message\":\"\"}{\"action\":\"refuse\",\"sql\":\"\",\"message\":\"x\"}",
            "SELECT 1",
            "{\"action\":\"sql\",\"sql\":\"SELECT 1\""
    })
    void rejectsExtraTextOrBrokenJson(String raw) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(FormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"sql\"", "42", "null"})
    void rejectsJsonThatIsNotAnObject(String raw) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(FormatException.class);
    }

    @Test
    void rejectsNonStringField() {
        assertThatThrownBy(() -> parser.parse("{\"action\":\"sql\",\"sql\":123,\"message\":\"\"}"))
                .isInstanceOf(FormatException.class)
                .hasMessageContaining("sql must be a string");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n"})
    void rejectsBlankOutput(String raw) {
        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(FormatException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsNullOutput() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(FormatException.class);
    }

    @Test
    void rejectsToolAsAnActionFromTheModel() {
        assertThatThrownBy(() -> parser.parse("{\"action\":\"tool\",\"sql\":\"\",\"message\":\"x\"}"))
                .isInstanceOf(FormatException.class)
                .hasMessageContaining("unknown action: tool");
    }

    @Test
    void parsesToolRouteWithRawArguments() {
        ParsedRoute route = parser.parseRoute("""
                {"route":"tool","tool":"stockout_rate","sku":"SKU-7","location":"광주센터","days":0,
                 "start_date":"2026-09-01","end_date":"2026-09-30","output":"ranking","top_n":3,"order":"desc",
                 "message":""}""");

        assertThat(route.route()).isEqualTo(Route.TOOL);
        assertThat(route.tool()).isEqualTo("stockout_rate");
        assertThat(route.args()).isEqualTo(new RawToolArgs(
                "SKU-7", "광주센터", 0, "2026-09-01", "2026-09-30", "ranking", 3, "desc"));
        assertThat(route.message()).isNull();
    }

    @Test
    void parsesSqlRouteWithEmptyArguments() {
        ParsedRoute route = parser.parseRoute("""
                ```json
                {"route":"sql","tool":"","sku":"","location":"","days":0,"start_date":"","end_date":"",
                 "output":"","top_n":0,"order":"","message":""}
                ```""");

        assertThat(route.route()).isEqualTo(Route.SQL);
        assertThat(route.tool()).isNull();
        assertThat(route.args()).isEqualTo(new RawToolArgs(null, null, 0, null, null, null, 0, null));
    }

    @Test
    void parsesClarifyAndRefuseRoutesWithMessage() {
        ParsedRoute clarify = parser.parseRoute("{\"route\":\"clarify\",\"message\":\"어느 품목인가요?\"}");
        ParsedRoute refuse = parser.parseRoute("{\"route\":\"refuse\",\"message\":\"변경할 수 없습니다.\"}");

        assertThat(clarify.route()).isEqualTo(Route.CLARIFY);
        assertThat(clarify.message()).isEqualTo("어느 품목인가요?");
        assertThat(refuse.route()).isEqualTo(Route.REFUSE);
        assertThat(refuse.message()).isEqualTo("변경할 수 없습니다.");
    }

    @Test
    void doesNotCheckToolNameOrArgumentValues() {
        ParsedRoute route = parser.parseRoute(
                "{\"route\":\"tool\",\"tool\":\"no_such_tool\",\"output\":\"sideways\",\"days\":-5}");

        assertThat(route.tool()).isEqualTo("no_such_tool");
        assertThat(route.args().output()).isEqualTo("sideways");
        assertThat(route.args().days()).isEqualTo(-5);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"route\":\"lookup\",\"message\":\"\"}",
            "{\"tool\":\"days_of_supply\",\"message\":\"\"}",
            "{\"route\":\"clarify\",\"message\":\"\"}",
            "{\"route\":\"refuse\"}",
            "{\"route\":\"tool\",\"tool\":\"days_of_supply\",\"days\":\"14\"}",
            "{\"route\":\"tool\",\"tool\":\"days_of_supply\",\"top_n\":2.5}",
            "{\"route\":\"tool\",\"tool\":\"days_of_supply\",\"days\":99999999999}",
            "{\"route\":\"tool\",\"tool\":\"days_of_supply\",\"sku\":7}",
            "{\"route\":\"sql\"} 추가 설명",
            "[{\"route\":\"sql\"}]",
            "  "
    })
    void rejectsMalformedRoute(String raw) {
        assertThatThrownBy(() -> parser.parseRoute(raw)).isInstanceOf(FormatException.class);
    }

    @Test
    void parsesAnswerAndTreatsEmptyAsNull() {
        assertThat(parser.parseAnswer("{\"answer\":\" 재고는 120개입니다. \"}")).isEqualTo("재고는 120개입니다.");
        assertThat(parser.parseAnswer("{\"answer\":\"\"}")).isNull();
        assertThatThrownBy(() -> parser.parseAnswer("{\"answer\":120}")).isInstanceOf(FormatException.class);
        assertThatThrownBy(() -> parser.parseAnswer("재고는 120개입니다.")).isInstanceOf(FormatException.class);
    }
}
