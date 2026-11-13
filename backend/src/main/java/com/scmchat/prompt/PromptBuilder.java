package com.scmchat.prompt;

import com.scmchat.config.ChatProperties;
import com.scmchat.executor.QueryResult;
import com.scmchat.llm.LlmMessage;
import com.scmchat.tool.ToolCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** prompts/v{n}.txt를 system 메시지로, 질문 원문을 user 메시지로 조립한다(API_SPEC 4.1). */
@Component
public class PromptBuilder {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[A-Z_]+}}");

    private final Path promptsDir;
    private final LocalDate asOfDate;
    private MetricsDictionary metricsDictionary;
    private FewShotLoader fewShotLoader;
    private SchemaSelector schemaSelector;
    private final Map<PromptVersion, String> routerSystemPrompts = new EnumMap<>(PromptVersion.class);

    public PromptBuilder(ChatProperties chatProperties) {
        this.promptsDir = Path.of(chatProperties.promptsDir());
        this.asOfDate = chatProperties.asOfDate();
        // 상대 경로는 작업 디렉터리에 따라 달라지므로 첫 요청이 아니라 기동할 때 확인한다.
        if (!Files.isDirectory(promptsDir)) {
            throw new IllegalStateException(
                    "chat.prompts-dir is not a directory: " + promptsDir.toAbsolutePath().normalize());
        }
        // 잘못된 버전과 깨진 프롬프트 파일은 첫 요청이 아니라 기동할 때 드러나게 한다.
        PromptVersion configured = PromptVersion.fromValue(chatProperties.promptVersion());
        if (configured.routed()) {
            buildRouter(configured, "기동 확인");
            build(PromptVersion.V1, "기동 확인");
            buildAnswer("기동 확인", new QueryResult(List.of("v"), List.of(), false));
        } else if (configured != PromptVersion.V0) {
            build(configured, "기동 확인");
        }
    }

    public List<LlmMessage> build(PromptVersion version, String question) {
        if (version.routed()) {
            throw new IllegalArgumentException(
                    version.value() + " has no single prompt: use buildRouter and build(V1)");
        }
        String template = readTemplate(version.value());
        String system = version == PromptVersion.V0 ? template : render(template, valuesFor(version, question));
        return List.of(LlmMessage.system(requireNoPlaceholder(system, version.value())), LlmMessage.user(question));
    }

    /**
     * 라우터의 메시지(API_SPEC 8.3, 9.2). system 메시지는 질문과 무관하게 같아서 버전마다 한 번만 만든다.
     * 모델 서버가 프롬프트 앞부분을 다시 계산하지 않아도 된다. v3은 v3_router.txt, tools.yml, router_fewshot.json을,
     * v4는 v4_router.txt, tools_v4.yml, router_fewshot_v4.json을 쓴다.
     */
    public List<LlmMessage> buildRouter(PromptVersion version, String question) {
        return List.of(LlmMessage.system(routerSystemPrompt(version)), LlmMessage.user(question));
    }

    private synchronized String routerSystemPrompt(PromptVersion version) {
        String cached = routerSystemPrompts.get(version);
        if (cached != null) {
            return cached;
        }
        RouterFiles files = switch (version) {
            case V3 -> new RouterFiles("v3_router", "tools.yml", "router_fewshot.json");
            case V4 -> new RouterFiles("v4_router", "tools_v4.yml", "router_fewshot_v4.json");
            default -> throw new IllegalArgumentException(version.value() + " has no router");
        };
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AS_OF_DATE", asOfDate.toString());
        values.put("DATE_RULES", metricsDictionary().dateRules());
        values.put("TOOLS", new ToolCatalog(promptsDir.resolve(files.tools())).render());
        values.put("ROUTER_FEWSHOT", FewShotLoader.forRouter(promptsDir.resolve(files.fewShot())).render());
        String rendered = requireNoPlaceholder(render(readTemplate(files.template()), values), files.template());
        routerSystemPrompts.put(version, rendered);
        return rendered;
    }

    private record RouterFiles(String template, String tools, String fewShot) {
    }

    /** 답변 문장 단계의 메시지(v3, v4 공통, API_SPEC 8.3). 질문과 조회 결과를 user 메시지로 준다. */
    public List<LlmMessage> buildAnswer(String question, QueryResult result) {
        String system = requireNoPlaceholder(
                render(readTemplate("v3_answer"), Map.of("AS_OF_DATE", asOfDate.toString())), "v3_answer");
        StringBuilder user = new StringBuilder();
        user.append("Question: ").append(question).append('\n');
        user.append("Columns: ").append(String.join(", ", result.columns())).append('\n');
        user.append("Rows:");
        for (List<Object> row : result.rows()) {
            user.append('\n').append(row.stream().map(PromptBuilder::cellText).collect(Collectors.joining(" | ")));
        }
        return List.of(LlmMessage.system(system), LlmMessage.user(user.toString()));
    }

    /** 숫자는 지수 표기 없이, NULL은 null로 적는다. */
    private static String cellText(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
    }

    /** v2의 실행 오류 재시도 메시지(API_SPEC 3.3). 이전 대화에 모델의 원문과 PostgreSQL 오류를 덧붙인다. */
    public List<LlmMessage> buildRetry(List<LlmMessage> prior, String rawReply, String error) {
        List<LlmMessage> messages = new ArrayList<>(prior);
        messages.add(LlmMessage.assistant(rawReply));
        messages.add(LlmMessage.user("The previous SQL failed with this PostgreSQL error:\n" + error
                + "\nReturn the corrected JSON in the same format."));
        return List.copyOf(messages);
    }

    /**
     * v0는 DDL이 파일에 고정돼 있어 채울 값이 없다. v1은 전체 스키마와 전체 지표 사전을 넣고,
     * v2는 질문에 필요한 테이블과 지표만 넣는다.
     */
    private Map<String, String> valuesFor(PromptVersion version, String question) {
        MetricsDictionary dictionary = metricsDictionary();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AS_OF_DATE", asOfDate.toString());
        boolean pruned = version == PromptVersion.V2;
        values.put("SCHEMA", pruned ? SchemaCatalog.ddlOf(tablesFor(question)) : SchemaCatalog.allDdl());
        values.put("DATE_RULES", dictionary.dateRules());
        values.put("METRICS", dictionary.select(question, pruned));
        values.put("FEWSHOT", fewShotLoader().render());
        return values;
    }

    /** 키워드 규칙이 고른 테이블에, 질문이 언급한 지표가 쓰는 테이블을 더한다. */
    Set<String> tablesFor(String question) {
        Set<String> tables = new LinkedHashSet<>(schemaSelector().select(question));
        tables.addAll(metricsDictionary().tablesFor(question));
        if (tables.stream().anyMatch(SchemaCatalog::isFactTable)) {
            tables.add("product");
            tables.add("location");
        }
        return tables;
    }

    private static String render(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            rendered = rendered.replace("{{" + value.getKey() + "}}", value.getValue());
        }
        return rendered;
    }

    // v0만 쓰는 실행에서는 지표 사전과 예시 파일을 읽지 않도록 처음 필요할 때 읽는다.
    private synchronized MetricsDictionary metricsDictionary() {
        if (metricsDictionary == null) {
            MetricsDictionary loaded = new MetricsDictionary(promptsDir.resolve("metrics.yml"));
            if (!loaded.asOfDate().equals(asOfDate)) {
                throw new IllegalStateException("metrics.yml asOfDate " + loaded.asOfDate()
                        + " differs from chat.as-of-date " + asOfDate);
            }
            metricsDictionary = loaded;
        }
        return metricsDictionary;
    }

    private synchronized SchemaSelector schemaSelector() {
        if (schemaSelector == null) {
            schemaSelector = new SchemaSelector(promptsDir.resolve("schema_rules.yml"));
        }
        return schemaSelector;
    }

    private synchronized FewShotLoader fewShotLoader() {
        if (fewShotLoader == null) {
            fewShotLoader = new FewShotLoader(promptsDir.resolve("fewshot.json"));
        }
        return fewShotLoader;
    }

    private String readTemplate(String name) {
        Path file = promptsDir.resolve(name + ".txt");
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read prompt template " + file.toAbsolutePath(), e);
        }
    }

    private static String requireNoPlaceholder(String rendered, String name) {
        Matcher matcher = PLACEHOLDER.matcher(rendered);
        if (matcher.find()) {
            throw new IllegalStateException(
                    "unresolved placeholder " + matcher.group() + " in prompt " + name);
        }
        return rendered;
    }
}
