package com.scmchat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolCatalogTest {

    // Gradle은 backend/를 작업 디렉터리로 테스트를 실행한다.
    private static final Path TOOLS_FILE = Path.of("../prompts/tools.yml");

    @Test
    void rendersEveryServerTool() {
        ToolCatalog catalog = new ToolCatalog(TOOLS_FILE);

        String rendered = catalog.render();

        assertThat(catalog.tools()).hasSize(ToolName.values().length);
        assertThat(rendered.lines()).hasSize(ToolName.values().length);
        Arrays.stream(ToolName.values()).forEach(tool -> assertThat(rendered).contains("- " + tool.value() + " ("));
        assertThat(rendered).contains("- days_of_supply (재고보유일수): ").contains("output: value | ranking.");
        assertThat(rendered).contains("- reorder_needed (재주문 필요): ").contains("output: list | count.");
        assertThat(rendered).contains("args: sku, location, start_date, end_date.");
    }

    @Test
    void failsWhenAToolIsMissing(@TempDir Path dir) throws IOException {
        String content = Files.readString(TOOLS_FILE, StandardCharsets.UTF_8);
        int cut = content.indexOf("  - name: reorder_needed");
        Path file = dir.resolve("tools.yml");
        Files.writeString(file, content.substring(0, cut), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ToolCatalog(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ from the server tools");
    }

    @Test
    void failsWhenOutputsDifferFromTheServer(@TempDir Path dir) throws IOException {
        String content = Files.readString(TOOLS_FILE, StandardCharsets.UTF_8)
                .replace("outputs: [list, count]", "outputs: [list, count, ranking]");
        Path file = dir.resolve("tools.yml");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ToolCatalog(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ from the server outputs");
    }
}
