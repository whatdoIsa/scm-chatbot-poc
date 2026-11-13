package com.scmchat.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.config.ChatProperties;
import com.scmchat.llm.LlmOutputParser;
import com.scmchat.llm.ParsedRoute;
import com.scmchat.llm.Route;
import com.scmchat.tool.Dimensions;
import com.scmchat.tool.ToolArgsValidator;
import com.scmchat.tool.ToolName;
import com.scmchat.tool.ToolValidation;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** prompts/router_fewshot.json의 예시가 서버가 실제로 받아들이는 호출인지 확인한다. */
class RouterFewShotTest {

    private static final Dimensions SEED_DIMENSIONS = new Dimensions() {
        @Override
        public boolean skuExists(String sku) {
            return sku.matches("SKU-0(0[1-9]|[1-4][0-9]|50)");
        }

        @Override
        public List<String> locationNames() {
            return List.of("서울센터", "부산센터", "대전센터", "광주센터", "대구센터");
        }

        @Override
        public List<String> regions() {
            return List.of("수도권", "영남권", "충청권", "호남권");
        }

        @Override
        public List<String> categories() {
            return List.of("스낵", "음료", "개인위생", "냉동식품", "생활용품");
        }
    };

    private final LlmOutputParser parser = new LlmOutputParser();
    private final ToolArgsValidator validator = new ToolArgsValidator(SEED_DIMENSIONS, new ChatProperties(
            "v3", LocalDate.of(2026, 11, 8), LocalDate.of(2026, 8, 10), 1000, 200, 20, "../prompts",
            new ChatProperties.Llm("fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120)));
    // Gradle은 backend/를 작업 디렉터리로 테스트를 실행한다.
    private final List<FewShotLoader.FewShot> examples =
            FewShotLoader.forRouter(Path.of("../prompts/router_fewshot.json")).examples();

    @Test
    void coversEveryRouteAndEveryTool() {
        List<ParsedRoute> routes = examples.stream().map(example -> parser.parseRoute(example.outputJson())).toList();

        assertThat(routes.stream().map(ParsedRoute::route).collect(Collectors.toSet()))
                .isEqualTo(EnumSet.allOf(Route.class));
        Set<String> tools = routes.stream()
                .filter(route -> route.route() == Route.TOOL)
                .map(ParsedRoute::tool)
                .collect(Collectors.toSet());
        assertThat(tools).isEqualTo(Arrays.stream(ToolName.values()).map(ToolName::value).collect(Collectors.toSet()));
    }

    @Test
    void toolExamplesPassArgumentValidation() {
        for (FewShotLoader.FewShot example : examples) {
            ParsedRoute route = parser.parseRoute(example.outputJson());
            if (route.route() != Route.TOOL) {
                continue;
            }
            ToolValidation validation = validator.validate(route.tool(), route.args());

            assertThat(validation.ok()).as(example.question() + ": " + validation.reason()).isTrue();
        }
    }

    @Test
    void nonToolExamplesLeaveToolArgumentsEmpty() {
        for (FewShotLoader.FewShot example : examples) {
            ParsedRoute route = parser.parseRoute(example.outputJson());
            if (route.route() == Route.TOOL) {
                continue;
            }
            assertThat(route.tool()).as(example.question()).isNull();
            assertThat(route.args().toArgsMap()).as(example.question()).isEmpty();
        }
    }
}
