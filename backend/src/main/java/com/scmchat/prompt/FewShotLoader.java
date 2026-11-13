package com.scmchat.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmchat.llm.LlmOutputParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** prompts/fewshot.json, router_fewshot.json의 예시를 읽는다(API_SPEC 4.3, 8.3). 각 예시의 output은 실제 응답 형식을 지켜야 한다. */
public class FewShotLoader {

    public record FewShot(String question, String outputJson) {
    }

    private final List<FewShot> examples;

    /** SQL 단계의 예시(ACTION 형식). */
    public FewShotLoader(Path file) {
        this(file, new LlmOutputParser()::parse);
    }

    /** v3 라우터의 예시(ROUTE 형식, API_SPEC 8.3). */
    public static FewShotLoader forRouter(Path file) {
        return new FewShotLoader(file, new LlmOutputParser()::parseRoute);
    }

    private FewShotLoader(Path file, Consumer<String> formatCheck) {
        ObjectMapper objectMapper = new ObjectMapper();
        List<FewShot> loaded = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(file));
            if (root == null || !root.isArray() || root.isEmpty()) {
                throw new IllegalStateException(file.getFileName() + " must be a non-empty JSON array");
            }
            for (JsonNode node : root) {
                JsonNode question = node.get("question");
                JsonNode output = node.get("output");
                if (question == null || !question.isTextual() || question.asText().isBlank() || output == null) {
                    throw new IllegalStateException("every few-shot example needs question and output");
                }
                String outputJson = objectMapper.writeValueAsString(output);
                formatCheck.accept(outputJson);
                loaded.add(new FewShot(question.asText().strip(), outputJson));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read few-shot examples " + file.toAbsolutePath().normalize(), e);
        }
        this.examples = List.copyOf(loaded);
    }

    public List<FewShot> examples() {
        return examples;
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        for (FewShot example : examples) {
            out.append("Question: ").append(example.question()).append('\n')
                    .append("Answer: ").append(example.outputJson()).append("\n\n");
        }
        return out.toString().stripTrailing();
    }
}
