package com.scmchat.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scmchat.llm.Action;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenSetLoaderTest {

    private static final String SQL_ITEM = """
            {"id": "Q01", "type": "SIMPLE_LOOKUP", "question": "SKU-007의 현재 재고는?", "expectedAction": "sql",
             "goldenSql": "SELECT 1", "orderSensitive": false, "note": "메모"}""";
    private static final String CLARIFY_ITEM = """
            {"id": "Q02", "type": "AMBIGUOUS", "question": "재고 어때?", "expectedAction": "clarify",
             "orderSensitive": false, "note": ""}""";

    private final GoldenSetLoader loader = new GoldenSetLoader();

    @TempDir
    Path dir;

    @Test
    void loadsSqlAndNonSqlItems() throws IOException {
        List<GoldenItem> items = loader.load(write("[" + SQL_ITEM + "," + CLARIFY_ITEM + "]"));

        assertThat(items).containsExactly(
                new GoldenItem("Q01", QuestionType.SIMPLE_LOOKUP, "SKU-007의 현재 재고는?", Action.SQL,
                        "SELECT 1", false, "메모"),
                new GoldenItem("Q02", QuestionType.AMBIGUOUS, "재고 어때?", Action.CLARIFY, null, false, null));
    }

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> loader.load(dir.resolve("none.json")))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("cannot read golden set");
    }

    @Test
    void rejectsEmptyArrayAndNonArray() throws IOException {
        assertThatThrownBy(() -> loader.load(write("[]"))).isInstanceOf(GoldenSetException.class);
        assertThatThrownBy(() -> loader.load(write(SQL_ITEM))).isInstanceOf(GoldenSetException.class);
    }

    @Test
    void rejectsBrokenJson() {
        assertThatThrownBy(() -> loader.load(write("[" + SQL_ITEM)))
                .isInstanceOf(GoldenSetException.class);
    }

    @Test
    void rejectsDuplicateId() {
        String second = SQL_ITEM.replace("SKU-007", "SKU-008");

        assertThatThrownBy(() -> loader.load(write("[" + SQL_ITEM + "," + second + "]")))
                .hasMessageContaining("duplicate id: Q01");
    }

    @Test
    void rejectsDuplicateQuestion() {
        String second = SQL_ITEM.replace("Q01", "Q03");

        assertThatThrownBy(() -> loader.load(write("[" + SQL_ITEM + "," + second + "]")))
                .hasMessageContaining("Q03: duplicate question");
    }

    @Test
    void rejectsMalformedId() {
        assertInvalid(SQL_ITEM.replace("Q01", "Q1"), "id must look like Q01");
        assertInvalid(SQL_ITEM.replace("Q01", "Q1000"), "id must look like Q01");
    }

    @Test
    void rejectsUnknownTypeAndAction() {
        assertInvalid(SQL_ITEM.replace("SIMPLE_LOOKUP", "LOOKUP"), "unknown type LOOKUP");
        assertInvalid(SQL_ITEM.replace("\"sql\"", "\"query\""), "unknown expectedAction query");
    }

    @Test
    void rejectsUnknownField() {
        assertInvalid(SQL_ITEM.replace("\"goldenSql\"", "\"goldensql\""), "unknown field goldensql");
    }

    @Test
    void rejectsSqlItemWithoutGoldenSql() {
        assertInvalid(SQL_ITEM.replace("SELECT 1", "  "), "goldenSql is required");
    }

    @Test
    void rejectsGoldenSqlOnNonSqlItem() {
        assertInvalid(CLARIFY_ITEM.replace("\"note\": \"\"", "\"note\": \"\", \"goldenSql\": \"SELECT 1\""),
                "goldenSql is only allowed");
    }

    @Test
    void rejectsActionThatDoesNotMatchType() {
        assertInvalid(CLARIFY_ITEM.replace("clarify", "refuse"), "type AMBIGUOUS requires expectedAction clarify");
        assertInvalid(CLARIFY_ITEM.replace("AMBIGUOUS", "METRIC"), "type METRIC requires expectedAction sql");
    }

    @Test
    void rejectsRankingThatIsNotOrderSensitive() {
        assertInvalid(SQL_ITEM.replace("SIMPLE_LOOKUP", "RANKING"), "RANKING items must be orderSensitive");
    }

    @Test
    void rejectsMissingOrNonBooleanOrderSensitive() {
        assertInvalid(SQL_ITEM.replace("\"orderSensitive\": false, ", ""), "orderSensitive must be true or false");
        assertInvalid(SQL_ITEM.replace("false", "\"false\""), "orderSensitive must be true or false");
    }

    @Test
    void rejectsBlankQuestion() {
        assertInvalid(SQL_ITEM.replace("SKU-007의 현재 재고는?", " "), "question is required");
    }

    private void assertInvalid(String item, String message) {
        assertThatThrownBy(() -> loader.load(write("[" + item + "]")))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining(message);
    }

    private Path write(String json) throws IOException {
        return Files.writeString(dir.resolve("golden_set.json"), json);
    }
}
