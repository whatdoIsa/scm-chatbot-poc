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
import com.scmchat.tool.ToolCall;
import com.scmchat.tool.ToolRouteGuard;
import com.scmchat.tool.ToolValidation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 라우터 개발 세트 2(36문항)로 v4와 v5의 경로를 비교한다. v5는 v4와 같은 라우터 출력에 서버 점검(ToolRouteGuard)을 더한 것이라
 * 라우터를 한 번만 부르고 두 버전의 경로를 함께 계산한다. 이 세트는 점검 규칙을 쓰기 전에 썼고 규칙을 고정할 때까지 결과를 보지 않았다.
 * SQL 단계의 결과는 채점하지 않는다. 경로(그리고 도구 경로라면 도구와 인자)만 본다. 결과는 build/router-dev/에 쓴다.
 */
@Tag("integration")
@Tag("ollama")
@SpringBootTest(properties = "chat.llm.provider=ollama")
class RouterDevSet2IT {

    record DevItem(String question, String route, String tool, Map<String, Object> args) {
    }

    record Choice(String route, String tool, Map<String, Object> args, String note) {
    }

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private LlmOutputParser parser;

    @Autowired
    private ToolArgsValidator validator;

    @Autowired
    private ToolRouteGuard guard;

    @Test
    void v5GuardDoesNotRouteWorseThanV4OnTheUnseenDevSet() throws IOException {
        assertThat(llmClient).isInstanceOf(OllamaLlmClient.class);
        List<DevItem> items;
        try (InputStream in = getClass().getResourceAsStream("/router_dev2_v5.json")) {
            items = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }
        int v4Exact = 0;
        int v5Exact = 0;
        StringBuilder out = new StringBuilder();
        for (DevItem item : items) {
            String raw = llmClient.generate(promptBuilder.buildRouter(PromptVersion.V5, item.question()),
                    OutputFormat.ROUTE_V4).rawContent();
            Choice v4;
            Choice v5;
            try {
                ParsedRoute parsed = parser.parseRoute(raw);
                v4 = v4Choice(parsed);
                v5 = v5Choice(item.question(), parsed);
            } catch (FormatException e) {
                v4 = new Choice(null, null, null, "FORMAT_ERROR");
                v5 = v4;
            }
            boolean ok4 = exact(item, v4);
            boolean ok5 = exact(item, v5);
            v4Exact += ok4 ? 1 : 0;
            v5Exact += ok5 ? 1 : 0;
            out.append(ok4 ? "v4 OK   " : "v4 MISS ").append(ok5 ? "v5 OK   " : "v5 MISS ")
                    .append(item.question()).append(" | expected ").append(item.route()).append(' ')
                    .append(Objects.toString(item.tool(), "")).append(' ').append(Objects.toString(item.args(), ""))
                    .append(" | v4 ").append(v4).append(" | v5 ").append(v5).append('\n');
        }
        String header = String.format("dev2 exact v4 %d/%d v5 %d/%d%n", v4Exact, items.size(), v5Exact, items.size());
        Path dir = Path.of("build/router-dev");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("dev2.txt"), header + out, StandardCharsets.UTF_8);
        System.out.print(header + out);

        assertThat(v5Exact).isGreaterThanOrEqualTo(v4Exact);
    }

    private Choice v4Choice(ParsedRoute parsed) {
        if (parsed.route() != Route.TOOL) {
            return new Choice(parsed.route().value(), null, null, "");
        }
        ToolValidation validation = validator.validate(parsed.tool(), parsed.args());
        return validation.ok()
                ? new Choice("tool", parsed.tool(), validation.call().toArgsMap(), "")
                : new Choice("tool", parsed.tool(), parsed.args().toArgsMap(), "TOOL_REJECTED");
    }

    private Choice v5Choice(String question, ParsedRoute parsed) {
        if (parsed.route() != Route.TOOL) {
            return new Choice(parsed.route().value(), null, null, "");
        }
        ToolValidation validation = validator.validate(parsed.tool(), parsed.args());
        if (!validation.ok()) {
            return new Choice("sql", null, null, "rejected");
        }
        ToolRouteGuard.Decision decision = guard.check(question, validation.call());
        if (decision.reroute()) {
            return new Choice("sql", null, null, decision.rerouteReason());
        }
        ToolCall call = decision.call();
        return new Choice("tool", call.tool().value(), call.toArgsMap(), decision.orderCorrected() ? "order" : "");
    }

    private static boolean exact(DevItem item, Choice choice) {
        if (!item.route().equals(choice.route())) {
            return false;
        }
        return !"tool".equals(item.route())
                || (item.tool().equals(choice.tool()) && item.args().equals(choice.args()));
    }
}
