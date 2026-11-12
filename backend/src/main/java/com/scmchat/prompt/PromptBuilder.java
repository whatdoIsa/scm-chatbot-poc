package com.scmchat.prompt;

import com.scmchat.config.ChatProperties;
import com.scmchat.llm.LlmMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** prompts/v{n}.txt를 system 메시지로, 질문 원문을 user 메시지로 조립한다(API_SPEC 4.1). */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[A-Z_]+}}");

    private final Path promptsDir;
    private final LocalDate asOfDate;
    private MetricsDictionary metricsDictionary;
    private FewShotLoader fewShotLoader;

    public PromptBuilder(ChatProperties chatProperties) {
        this.promptsDir = Path.of(chatProperties.promptsDir());
        this.asOfDate = chatProperties.asOfDate();
        // 상대 경로는 작업 디렉터리에 따라 달라지므로 첫 요청이 아니라 기동할 때 확인한다.
        if (!Files.isDirectory(promptsDir)) {
            throw new IllegalStateException(
                    "chat.prompts-dir is not a directory: " + promptsDir.toAbsolutePath().normalize());
        }
        PromptVersion configured = PromptVersion.fromValue(chatProperties.promptVersion());
        if (!supports(configured)) {
            log.warn("chat.prompt-version={} is not implemented yet; /api/chat will answer 501 until it is",
                    configured.value());
        }
    }

    public boolean supports(PromptVersion version) {
        return version == PromptVersion.V0 || version == PromptVersion.V1;
    }

    public List<LlmMessage> build(PromptVersion version, String question) {
        if (!supports(version)) {
            throw new UnsupportedOperationException("prompt version " + version.value() + " is not implemented yet");
        }
        String template = readTemplate(version);
        String system = version == PromptVersion.V0 ? template : render(template, valuesFor(version, question));
        return List.of(LlmMessage.system(requireNoPlaceholder(system, version)), LlmMessage.user(question));
    }

    /** v0는 DDL이 파일에 고정돼 있어 채울 값이 없다. v1은 전체 스키마와 전체 지표 사전을 넣는다. */
    private Map<String, String> valuesFor(PromptVersion version, String question) {
        MetricsDictionary dictionary = metricsDictionary();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AS_OF_DATE", asOfDate.toString());
        values.put("SCHEMA", SchemaCatalog.allDdl());
        values.put("DATE_RULES", dictionary.dateRules());
        values.put("METRICS", dictionary.select(question, false));
        values.put("FEWSHOT", fewShotLoader().render());
        return values;
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

    private synchronized FewShotLoader fewShotLoader() {
        if (fewShotLoader == null) {
            fewShotLoader = new FewShotLoader(promptsDir.resolve("fewshot.json"));
        }
        return fewShotLoader;
    }

    private String readTemplate(PromptVersion version) {
        Path file = promptsDir.resolve(version.value() + ".txt");
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read prompt template " + file.toAbsolutePath(), e);
        }
    }

    private static String requireNoPlaceholder(String rendered, PromptVersion version) {
        Matcher matcher = PLACEHOLDER.matcher(rendered);
        if (matcher.find()) {
            throw new IllegalStateException(
                    "unresolved placeholder " + matcher.group() + " in prompt " + version.value());
        }
        return rendered;
    }
}
