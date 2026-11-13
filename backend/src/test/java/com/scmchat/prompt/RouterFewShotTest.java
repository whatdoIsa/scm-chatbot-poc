package com.scmchat.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.config.ChatProperties;
import com.scmchat.llm.LlmOutputParser;
import com.scmchat.llm.ParsedRoute;
import com.scmchat.llm.Route;
import com.scmchat.tool.Dimensions;
import com.scmchat.tool.ToolArgsValidator;
import com.scmchat.tool.ToolCall;
import com.scmchat.tool.ToolName;
import com.scmchat.tool.ToolValidation;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** v3·v4 라우터 예시가 서버가 실제로 받아들이는 호출인지, 질문에 없는 값을 인자로 가르치지 않는지 확인한다. */
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
    private static final Pattern SKU_IN_TEXT = Pattern.compile("(?i)SKU-(\\d{1,3})");

    static Stream<String> exampleFiles() {
        return Stream.of("router_fewshot.json", "router_fewshot_v4.json");
    }

    private static List<FewShotLoader.FewShot> examples(String file) {
        // Gradle은 backend/를 작업 디렉터리로 테스트를 실행한다.
        return FewShotLoader.forRouter(Path.of("../prompts", file)).examples();
    }

    @ParameterizedTest
    @MethodSource("exampleFiles")
    void coversEveryRouteAndEveryTool(String file) {
        List<FewShotLoader.FewShot> examples = examples(file);
        List<ParsedRoute> routes = examples.stream().map(example -> parser.parseRoute(example.outputJson())).toList();

        assertThat(routes.stream().map(ParsedRoute::route).collect(Collectors.toSet()))
                .isEqualTo(EnumSet.allOf(Route.class));
        Set<String> tools = routes.stream()
                .filter(route -> route.route() == Route.TOOL)
                .map(ParsedRoute::tool)
                .collect(Collectors.toSet());
        assertThat(tools).isEqualTo(Arrays.stream(ToolName.values()).map(ToolName::value).collect(Collectors.toSet()));
    }

    @ParameterizedTest
    @MethodSource("exampleFiles")
    void toolExamplesPassArgumentValidation(String file) {
        for (FewShotLoader.FewShot example : examples(file)) {
            ParsedRoute route = parser.parseRoute(example.outputJson());
            if (route.route() != Route.TOOL) {
                continue;
            }
            ToolValidation validation = validator.validate(route.tool(), route.args());

            assertThat(validation.ok()).as(example.question() + ": " + validation.reason()).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("exampleFiles")
    void nonToolExamplesLeaveToolArgumentsEmpty(String file) {
        for (FewShotLoader.FewShot example : examples(file)) {
            ParsedRoute route = parser.parseRoute(example.outputJson());
            if (route.route() == Route.TOOL) {
                continue;
            }
            assertThat(route.tool()).as(example.question()).isNull();
            assertThat(route.args().toArgsMap()).as(example.question()).isEmpty();
        }
    }

    /**
     * 예시의 인자 값은 모두 그 질문에 적힌 것이어야 한다. 질문에 없는 값을 채운 예시는 질문에 없는 SKU를 인자로 넣는
     * 오류(v3 측정 3건, PRD 11.1)를 가르친다.
     */
    @ParameterizedTest
    @MethodSource("exampleFiles")
    void toolArgumentsComeOnlyFromTheQuestion(String file) {
        for (FewShotLoader.FewShot example : examples(file)) {
            ParsedRoute route = parser.parseRoute(example.outputJson());
            if (route.route() != Route.TOOL) {
                continue;
            }
            ToolCall call = validator.validate(route.tool(), route.args()).call();
            String question = example.question();
            Matcher sku = SKU_IN_TEXT.matcher(question);
            if (call.sku() == null) {
                assertThat(sku.find()).as(question + ": names an item but sku is empty").isFalse();
            } else {
                assertThat(sku.find()).as(question).isTrue();
                assertThat(Integer.parseInt(sku.group(1))).as(question)
                        .isEqualTo(Integer.parseInt(call.sku().substring(4)));
            }
            if (call.location() != null) {
                assertThat(question).contains(call.location().replace("센터", ""));
            }
            if (call.region() != null) {
                assertThat(question).contains(call.region());
            }
            if (call.category() != null) {
                assertThat(question).contains(call.category());
            }
            if (call.abcClass() != null) {
                assertThat(question).contains(call.abcClass() + "등급");
            }
        }
    }

    /** v4 예시는 조건 인자 세 가지를 각각 한 번 이상 보여 준다. 예시가 없으면 모델이 조건 질문을 SQL로 보낸다. */
    @Test
    void v4ExamplesShowEveryConditionArgument() {
        List<ToolCall> calls = examples("router_fewshot_v4.json").stream()
                .map(example -> parser.parseRoute(example.outputJson()))
                .filter(route -> route.route() == Route.TOOL)
                .map(route -> validator.validate(route.tool(), route.args()).call())
                .toList();

        assertThat(calls).anySatisfy(call -> assertThat(call.region()).isNotNull());
        assertThat(calls).anySatisfy(call -> assertThat(call.category()).isNotNull());
        assertThat(calls).anySatisfy(call -> assertThat(call.abcClass()).isNotNull());
        // 조건이 없는 호출도 남겨 두어 모든 질문에 조건을 붙이지 않게 한다.
        assertThat(calls).anySatisfy(call -> assertThat(call.toArgsMap()).containsOnlyKeys("output"));
    }

    /**
     * 라우터 개발 세트(test/resources/router_dev_v4.json, RouterDevSetIT)는 평가 세트 대신 프롬프트를 다듬을 때 본다.
     * 네 골든셋과 라우터 예시 어느 것과도 겹치면 안 되고, 기대한 도구 호출은 서버가 받아들이는 호출이어야 한다.
     */
    @Test
    void routerDevSetIsDisjointFromGoldenSetsAndExamplesAndHasValidToolCalls() throws java.io.IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String file : List.of("golden_set.json", "golden_set_deep.json", "golden_set_v3.json", "golden_set_v4.json")) {
            mapper.readTree(Path.of("../eval", file).toFile())
                    .forEach(item -> seen.add(normalize(item.get("question").asText())));
        }
        exampleFiles().flatMap(file -> examples(file).stream()).forEach(example -> seen.add(normalize(example.question())));
        // SQL 단계 예시(fewshot.json)도 본다. 그 5개는 v3 라우터 예시 5개와 같은 질문이라(v3 설계) 개수는 늘지 않는다.
        new FewShotLoader(Path.of("../prompts/fewshot.json")).examples()
                .forEach(example -> seen.add(normalize(example.question())));
        assertThat(seen).hasSize(110 + 10 + 23);

        com.fasterxml.jackson.databind.JsonNode dev =
                mapper.readTree(Path.of("src/test/resources/router_dev_v4.json").toFile());
        com.fasterxml.jackson.databind.JsonNode dev2 =
                mapper.readTree(Path.of("src/test/resources/router_dev2_v5.json").toFile());
        assertThat(dev).hasSize(41);
        assertThat(dev2).hasSize(36);
        java.util.List<com.fasterxml.jackson.databind.JsonNode> all = new java.util.ArrayList<>();
        dev.forEach(all::add);
        dev2.forEach(all::add);
        assertThat(all.stream().map(item -> normalize(item.get("question").asText())).distinct()).hasSize(77);
        for (com.fasterxml.jackson.databind.JsonNode item : all) {
            String question = item.get("question").asText();
            assertThat(seen).as(question).doesNotContain(normalize(question));
            if (!"tool".equals(item.get("route").asText())) {
                assertThat(item.has("tool")).as(question).isFalse();
                continue;
            }
            com.fasterxml.jackson.databind.JsonNode args = item.get("args");
            ToolValidation validation = validator.validate(item.get("tool").asText(), new com.scmchat.tool.RawToolArgs(
                    args.path("sku").asText(""), args.path("location").asText(""), args.path("days").asInt(0),
                    args.path("start_date").asText(""), args.path("end_date").asText(""), args.path("output").asText(""),
                    args.path("top_n").asInt(0), args.path("order").asText(""), args.path("region").asText(""),
                    args.path("category").asText(""), args.path("abc_class").asText("")));
            assertThat(validation.ok()).as(question + ": " + validation.reason()).isTrue();
            assertThat(validation.call().toArgsMap()).as(question)
                    .isEqualTo(mapper.convertValue(args, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                    }));
        }
    }

    private static String normalize(String question) {
        return question.replaceAll("\\s+", "");
    }
}
