package com.scmchat.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scmchat.config.ChatProperties;
import com.scmchat.llm.LlmMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @ParameterizedTest
    @EnumSource(value = PromptVersion.class, names = {"V1", "V2"})
    void laterVersionsAreNotImplementedYet(PromptVersion version) {
        assertThatThrownBy(() -> builder.build(version, QUESTION))
                .isInstanceOf(UnsupportedOperationException.class);
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
    void parsesVersionValues() {
        assertThat(PromptVersion.fromValue("v0")).isEqualTo(PromptVersion.V0);
        assertThat(PromptVersion.V2.value()).isEqualTo("v2");
        assertThatThrownBy(() -> PromptVersion.fromValue("v9")).isInstanceOf(IllegalArgumentException.class);
    }

    private static PromptBuilder builderFor(Path promptsDir) {
        ChatProperties.Llm llm = new ChatProperties.Llm(
                "fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120);
        return new PromptBuilder(new ChatProperties(
                "v0", LocalDate.of(2026, 11, 8), 1000, 200, promptsDir.toString(), llm));
    }
}
