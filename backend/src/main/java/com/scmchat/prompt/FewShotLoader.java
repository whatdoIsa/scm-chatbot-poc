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

/** prompts/fewshot.json의 예시를 읽는다(API_SPEC 4.3). 각 예시의 output은 실제 응답 형식을 지켜야 한다. */
public class FewShotLoader {

    public record FewShot(String question, String outputJson) {
    }

    private final List<FewShot> examples;

    public FewShotLoader(Path file) {
        ObjectMapper objectMapper = new ObjectMapper();
        LlmOutputParser parser = new LlmOutputParser();
        List<FewShot> loaded = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(file));
            if (root == null || !root.isArray() || root.isEmpty()) {
                throw new IllegalStateException("fewshot.json must be a non-empty JSON array");
            }
            for (JsonNode node : root) {
                JsonNode question = node.get("question");
                JsonNode output = node.get("output");
                if (question == null || !question.isTextual() || question.asText().isBlank() || output == null) {
                    throw new IllegalStateException("every few-shot example needs question and output");
                }
                String outputJson = objectMapper.writeValueAsString(output);
                parser.parse(outputJson);
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
