package com.scmchat.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scmchat.config.ChatProperties;
import com.scmchat.executor.QueryResult;
import com.scmchat.llm.LlmMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptBuilderTest {

    private static final String QUESTION = "SKU-007의 현재 재고는?";

    // Gradle은 backend/를 작업 디렉터리로 테스트를 실행한다.
    private final PromptBuilder builder = builderFor(Path.of("../prompts"));

    @Test
    void buildsSystemAndUserMessagesForV0() {
        List<LlmMessage> messages = builder.build(PromptVersion.V0, QUESTION);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1)).isEqualTo(LlmMessage.user(QUESTION));
    }

    @Test
    void v0ContainsAllSixTablesWithoutSchemaPrefix() {
        String system = builder.build(PromptVersion.V0, QUESTION).get(0).content();

        assertThat(system).contains(
                "CREATE TABLE product (", "CREATE TABLE location (", "CREATE TABLE sales_daily (",
                "CREATE TABLE inventory_daily (", "CREATE TABLE forecast_weekly (", "CREATE TABLE supply_policy (");
        assertThat(system).doesNotContain("scm.");
    }

    @Test
    void v0DescribesTheThreeActions() {
        String system = builder.build(PromptVersion.V0, QUESTION).get(0).content();

        assertThat(system).contains("\"sql\"", "\"clarify\"", "\"refuse\"", "\"action\"", "\"message\"");
    }

    @Test
    void v0HasNoAsOfDateMetricsOrFewShot() {
        String system = builder.build(PromptVersion.V0, QUESTION).get(0).content();

        assertThat(system).doesNotContain("2026", "as-of", "as of", "AS_OF_DATE", "METRICS", "FEWSHOT");
    }

    @Test
    void v0DoesNotLeakTheQuestionIntoTheSystemMessage() {
        String system = builder.build(PromptVersion.V0, QUESTION).get(0).content();

        assertThat(system).doesNotContain("SKU-007");
    }

    @Test
    void v1ContainsAsOfDateAndForbidsClockFunctions() {
        String system = builder.build(PromptVersion.V1, QUESTION).get(0).content();

        assertThat(system).contains("The as-of date (\"today\") is 2026-11-08", "Never use CURRENT_DATE");
    }

    @Test
    void v1ContainsFullSchemaDateRulesAndAllMetrics() {
        String system = builder.build(PromptVersion.V1, QUESTION).get(0).content();

        assertThat(system).contains(
                "CREATE TABLE product (", "CREATE TABLE location (", "CREATE TABLE sales_daily (",
                "CREATE TABLE inventory_daily (", "CREATE TABLE forecast_weekly (", "CREATE TABLE supply_policy (");
        assertThat(system).contains("- 지난주: ", "2026-10-26 ~ 2026-11-01", "- 최근 13주: ", "- 데이터 범위 밖 기간: refuse");
        assertThat(system).contains("- 일평균 판매량(최근 N일): ", "- 재고보유일수: ", "- 결품률: ",
                "- 안전재고 미달: ", "- 재주문 필요: ", "- 예측 정확도: ");
        assertThat(system).contains("SKU-7은 SKU-007로 정규화한다", "재고, 현재고 -> inventory_daily.on_hand");
    }

    @Test
    void v1ContainsFiveFewShotExamples() {
        String system = builder.build(PromptVersion.V1, QUESTION).get(0).content();

        assertThat(system.split("\nQuestion: ", -1)).hasSize(6);
        assertThat(system).contains("Question: SKU-012의 어제 판매량은?");
    }

    @Test
    void v1SendsOnlyTheQuestionAsUserMessageAndLeavesNoPlaceholder() {
        List<LlmMessage> messages = builder.build(PromptVersion.V1, QUESTION);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isEqualTo(LlmMessage.user(QUESTION));
        assertThat(messages.get(0).content()).doesNotContain("{{", "}}");
    }

    @Test
    void v0IsNotAffectedByV1Files() {
        String v0 = builder.build(PromptVersion.V0, QUESTION).get(0).content();

        assertThat(v0).doesNotContain("2026-11-08", "Question: ", "재고보유일수");
    }

    @Test
    void failsWhenPlaceholderRemains(@TempDir Path promptsDir) throws IOException {
        Files.writeString(promptsDir.resolve("v0.txt"), "Schema:\n{{SCHEMA}}\nReturn JSON.");

        assertThatThrownBy(() -> builderFor(promptsDir).build(PromptVersion.V0, QUESTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{{SCHEMA}}");
    }

    @Test
    void failsWhenTemplateFileIsMissing(@TempDir Path promptsDir) {
        assertThatThrownBy(() -> builderFor(promptsDir).build(PromptVersion.V0, QUESTION))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("v0.txt");
    }

    @Test
    void failsAtStartupWhenPromptsDirDoesNotExist(@TempDir Path parent) {
        assertThatThrownBy(() -> builderFor(parent.resolve("missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat.prompts-dir");
    }

    @Test
    void v2ContainsOnlyTablesAndMetricsRelevantToTheQuestion() {
        String system = builder.build(PromptVersion.V2, "SKU-008의 최근 13주 예측 정확도는?").get(0).content();

        assertThat(system).contains("CREATE TABLE forecast_weekly (", "CREATE TABLE sales_daily (",
                "CREATE TABLE product (", "CREATE TABLE location (", "- 예측 정확도: ");
        assertThat(system).doesNotContain("CREATE TABLE supply_policy (", "CREATE TABLE inventory_daily (",
                "- 재고보유일수: ", "- 결품률: ");
        assertThat(system).contains("The as-of date (\"today\") is 2026-11-08", "- 지난주: ");
        assertThat(system.split("\nQuestion: ", -1)).hasSize(6);
        assertThat(system).doesNotContain("{{", "}}");
    }

    @Test
    void v2FallsBackToFullSchemaWhenNoKeywordMatches() {
        String system = builder.build(PromptVersion.V2, "안녕하세요").get(0).content();

        assertThat(system).contains("CREATE TABLE product (", "CREATE TABLE supply_policy (",
                "CREATE TABLE forecast_weekly (", "CREATE TABLE inventory_daily (");
    }

    @Test
    void v2AddsTablesNeededByTheMentionedMetric() {
        assertThat(builder.tablesFor("결품률이 높은 곳은?"))
                .contains("inventory_daily", "product", "location");
        assertThat(builder.tablesFor("일평균 판매량은?")).contains("sales_daily");
    }

    @Test
    void buildsRetryMessagesFromPriorConversation() {
        List<LlmMessage> prior = builder.build(PromptVersion.V2, QUESTION);

        List<LlmMessage> retry = builder.buildRetry(prior, "{\"action\":\"sql\"}", "ERROR: column \"x\" does not exist");

        assertThat(retry).hasSize(4);
        assertThat(retry.subList(0, 2)).isEqualTo(prior);
        assertThat(retry.get(2)).isEqualTo(LlmMessage.assistant("{\"action\":\"sql\"}"));
        assertThat(retry.get(3)).isEqualTo(LlmMessage.user("The previous SQL failed with this PostgreSQL error:\n"
                + "ERROR: column \"x\" does not exist\nReturn the corrected JSON in the same format."));
    }

    @Test
    void rejectsUnknownConfiguredVersionAtStartup() {
        ChatProperties.Llm llm = new ChatProperties.Llm(
                "fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120);
        ChatProperties properties = new ChatProperties("v9", LocalDate.of(2026, 11, 8), LocalDate.of(2026, 8, 10), 1000, 200, 20, "../prompts", llm);

        assertThatThrownBy(() -> new PromptBuilder(properties)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routerPromptHasAsOfDateDateRulesToolsAndExamples() {
        List<LlmMessage> messages = builder.buildRouter(QUESTION);
        String system = messages.get(0).content();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isEqualTo(LlmMessage.user(QUESTION));
        assertThat(system).contains("The as-of date (\"today\") is 2026-11-08.");
        assertThat(system).contains("지난주", "2026-10-26 ~ 2026-11-01");
        assertThat(system).contains("- avg_daily_sales (", "- days_of_supply (", "- stockout_rate (",
                "- forecast_accuracy (", "- below_safety_stock (", "- reorder_needed (");
        assertThat(system).contains("\"tool\"", "\"sql\"", "\"clarify\"", "\"refuse\"");
        assertThat(system.split("Question: ", -1)).hasSize(11);
        assertThat(system).doesNotContain("{{", "CREATE TABLE");
    }

    @Test
    void routerSystemPromptDoesNotDependOnTheQuestion() {
        String first = builder.buildRouter(QUESTION).get(0).content();
        String second = builder.buildRouter("재고보유일수가 가장 짧은 품목 5개는?").get(0).content();

        assertThat(second).isEqualTo(first);
        assertThat(first).doesNotContain(QUESTION);
    }

    @Test
    void v3HasNoSinglePrompt() {
        assertThatThrownBy(() -> builder.build(PromptVersion.V3, QUESTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buildRouter");
    }

    @Test
    void startsWithV3Configured() {
        ChatProperties.Llm llm = new ChatProperties.Llm(
                "fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120);

        new PromptBuilder(new ChatProperties(
                "v3", LocalDate.of(2026, 11, 8), LocalDate.of(2026, 8, 10), 1000, 200, 20, "../prompts", llm));
    }

    @Test
    void answerPromptCarriesTheQuestionAndTheResultTable() {
        QueryResult result = new QueryResult(List.of("sku", "days_of_supply", "week_start"), List.of(
                java.util.Arrays.asList("SKU-033", new java.math.BigDecimal("4.8720000000"), LocalDate.of(2026, 11, 2)),
                java.util.Arrays.asList("SKU-040", new java.math.BigDecimal("1E+1"), null)), false);

        List<LlmMessage> messages = builder.buildAnswer("재고보유일수가 짧은 품목은?", result);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content())
                .contains("The as-of date (\"today\") is 2026-11-08.", "\"answer\"")
                .doesNotContain("{{", "CREATE TABLE");
        assertThat(messages.get(1)).isEqualTo(LlmMessage.user("""
                Question: 재고보유일수가 짧은 품목은?
                Columns: sku, days_of_supply, week_start
                Rows:
                SKU-033 | 4.8720000000 | 2026-11-02
                SKU-040 | 10 | null"""));
    }

    @Test
    void parsesVersionValues() {
        assertThat(PromptVersion.fromValue("v0")).isEqualTo(PromptVersion.V0);
        assertThat(PromptVersion.V2.value()).isEqualTo("v2");
        assertThat(PromptVersion.fromValue("v3")).isEqualTo(PromptVersion.V3);
        assertThatThrownBy(() -> PromptVersion.fromValue("v9")).isInstanceOf(IllegalArgumentException.class);
    }

    private static PromptBuilder builderFor(Path promptsDir) {
        ChatProperties.Llm llm = new ChatProperties.Llm(
                "fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120);
        return new PromptBuilder(new ChatProperties(
                "v0", LocalDate.of(2026, 11, 8), LocalDate.of(2026, 8, 10), 1000, 200, 20, promptsDir.toString(), llm));
    }
}
