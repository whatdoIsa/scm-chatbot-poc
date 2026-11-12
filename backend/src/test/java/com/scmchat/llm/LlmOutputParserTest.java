package com.scmchat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
