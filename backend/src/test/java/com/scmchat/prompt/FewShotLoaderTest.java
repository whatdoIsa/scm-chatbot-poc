package com.scmchat.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scmchat.llm.FormatException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FewShotLoaderTest {

    @Test
    void loadsFiveExamplesCoveringAllActions() {
        FewShotLoader loader = new FewShotLoader(Path.of("../prompts/fewshot.json"));

        assertThat(loader.examples()).hasSize(5);
        assertThat(loader.render()).contains("\"action\":\"sql\"", "\"action\":\"clarify\"", "\"action\":\"refuse\"");
        assertThat(loader.render().split("\nQuestion: ", -1)).hasSize(5);
    }

    @Test
    void examplesNeverUseClockFunctionsOrSchemaPrefix() {
        String rendered = new FewShotLoader(Path.of("../prompts/fewshot.json")).render();

        assertThat(rendered.toLowerCase()).doesNotContain("current_date", "now()", "scm.");
    }

    @Test
    void rejectsExampleWhoseOutputBreaksTheFormat(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("fewshot.json"),
                "[{\"question\": \"질문\", \"output\": {\"action\": \"sql\", \"sql\": \"\", \"message\": \"\"}}]");

        assertThatThrownBy(() -> new FewShotLoader(file)).isInstanceOf(FormatException.class);
    }

    @Test
    void rejectsEmptyFile(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("fewshot.json"), "[]");

        assertThatThrownBy(() -> new FewShotLoader(file)).isInstanceOf(IllegalStateException.class);
    }
}
