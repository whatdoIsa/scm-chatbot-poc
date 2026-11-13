package com.scmchat.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmchat.llm.FormatException;
import com.scmchat.llm.LlmClient;
import com.scmchat.llm.LlmOutputParser;
import com.scmchat.llm.OllamaLlmClient;
import com.scmchat.llm.OutputFormat;
import com.scmchat.llm.ParsedRoute;
import com.scmchat.llm.Route;
import com.scmchat.tool.ToolArgsValidator;
import com.scmchat.tool.ToolValidation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 라우터 개발용 질문 41개(test/resources/router_dev_v4.json)로 실제 모델의 경로·도구·인자 선택을 잰다.
 * 이 질문들은 네 골든셋 어느 문항과도 겹치지 않고, 평가 세트를 보지 않고 썼다. v4 라우터 프롬프트를 다듬는 동안
 * 평가 세트 대신 이 세트를 본다. v3 라우터도 같은 질문으로 재서 비교용으로 남긴다(조건 인자가 없는 v3에는 불리하다).
 * 결과는 build/router-dev/에 문항별로 쓴다.
 */
@Tag("integration")
@Tag("ollama")
@SpringBootTest(properties = "chat.llm.provider=ollama")
class RouterDevSetIT {

    /** v4 라우터가 이 아래로 내려가면 프롬프트나 예시가 망가진 것이다. 프롬프트를 고정한 뒤 39/41이 나왔고 여기서 여유를 둔 하한이다. */
    private static final double V4_ROUTE_ACCURACY_FLOOR = 0.90;
    private static final double V4_EXACT_ACCURACY_FLOOR = 0.85;

    record DevItem(String question, String route, String tool, Map<String, Object> args) {
    }

    record Outcome(DevItem item, String route, String tool, Map<String, Object> args, String note) {

        boolean routeCorrect() {
            return item.route().equals(route);
        }

        /** 경로가 맞고, 도구 경로라면 도구 이름과 검증을 거친 인자까지 모두 같다. */
        boolean exact() {
            if (!routeCorrect()) {
                return false;
            }
            return !"tool".equals(item.route()) || (item.tool().equals(tool) && item.args().equals(args));
        }
    }

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private LlmOutputParser parser;

    @Autowired
    private ToolArgsValidator toolArgsValidator;

    @Test
    void v4RouterChoosesRoutesToolsAndArgumentsOnTheDevSet() throws IOException {
        assertThat(llmClient).isInstanceOf(OllamaLlmClient.class);
        List<DevItem> items = devItems();

        List<Outcome> v4 = run(items, PromptVersion.V4, OutputFormat.ROUTE_V4);
        List<Outcome> v3 = run(items, PromptVersion.V3, OutputFormat.ROUTE);
        write("v4", v4);
        write("v3", v3);
        // 잰 프롬프트를 결과 옆에 남긴다. 프롬프트를 고칠 때 무엇으로 잰 결과인지 확인할 수 있다.
        Files.writeString(Path.of("build/router-dev/v4_system.txt"),
                promptBuilder.buildRouter(PromptVersion.V4, "").get(0).content(), StandardCharsets.UTF_8);

        assertThat(v4).noneMatch(outcome -> outcome.note().startsWith("FORMAT_ERROR"));
        assertThat(accuracy(v4, Outcome::routeCorrect)).isGreaterThanOrEqualTo(V4_ROUTE_ACCURACY_FLOOR);
        assertThat(accuracy(v4, Outcome::exact)).isGreaterThanOrEqualTo(V4_EXACT_ACCURACY_FLOOR);
    }

    private List<Outcome> run(List<DevItem> items, PromptVersion version, OutputFormat format) {
        List<Outcome> outcomes = new ArrayList<>();
        for (DevItem item : items) {
            String raw = llmClient.generate(promptBuilder.buildRouter(version, item.question()), format).rawContent();
            ParsedRoute parsed;
            try {
                parsed = parser.parseRoute(raw);
            } catch (FormatException e) {
                outcomes.add(new Outcome(item, null, null, null, "FORMAT_ERROR " + e.getMessage()));
                continue;
            }
            if (parsed.route() != Route.TOOL) {
                outcomes.add(new Outcome(item, parsed.route().value(), null, null, ""));
                continue;
            }
            ToolValidation validation = toolArgsValidator.validate(parsed.tool(), parsed.args());
            Map<String, Object> args = validation.ok()
                    ? validation.call().toArgsMap()
                    : parsed.args().toArgsMap();
            outcomes.add(new Outcome(item, "tool", parsed.tool(), args,
                    validation.ok() ? "" : "TOOL_REJECTED " + validation.reason()));
        }
        return outcomes;
    }

    private static double accuracy(List<Outcome> outcomes, java.util.function.Predicate<Outcome> correct) {
        return (double) outcomes.stream().filter(correct).count() / outcomes.size();
    }

    private static void write(String version, List<Outcome> outcomes) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append(String.format("%s route %d/%d exact %d/%d%n", version,
                outcomes.stream().filter(Outcome::routeCorrect).count(), outcomes.size(),
                outcomes.stream().filter(Outcome::exact).count(), outcomes.size()));
        for (Outcome outcome : outcomes) {
            out.append(outcome.exact() ? "OK   " : "MISS ")
                    .append(outcome.item().question()).append(" | expected ").append(outcome.item().route())
                    .append(' ').append(Objects.toString(outcome.item().tool(), ""))
                    .append(' ').append(Objects.toString(outcome.item().args(), ""))
                    .append(" | got ").append(outcome.route()).append(' ')
                    .append(Objects.toString(outcome.tool(), "")).append(' ')
                    .append(Objects.toString(outcome.args(), "")).append(' ').append(outcome.note())
                    .append('\n');
        }
        Path dir = Path.of("build/router-dev");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(version + ".txt"), out, StandardCharsets.UTF_8);
        System.out.print(out);
    }

    private static List<DevItem> devItems() throws IOException {
        try (InputStream in = RouterDevSetIT.class.getResourceAsStream("/router_dev_v4.json")) {
            List<DevItem> items = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
            return items.stream()
                    .map(item -> new DevItem(item.question(), item.route(), item.tool(),
                            item.args() == null ? null : normalize(item.args())))
                    .toList();
        }
    }

    /** JSON의 정수는 Integer로 읽히고 검증기가 만든 인자 맵도 Integer라 그대로 비교할 수 있다. */
    private static Map<String, Object> normalize(Map<String, Object> args) {
        return new java.util.LinkedHashMap<>(args);
    }
}
