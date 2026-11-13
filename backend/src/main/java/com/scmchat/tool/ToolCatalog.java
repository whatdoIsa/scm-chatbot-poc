package com.scmchat.tool;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

/**
 * prompts/tools.yml의 도구 목록을 라우터 프롬프트에 넣을 문장으로 만든다(API_SPEC 8.3).
 * 파일의 도구 이름과 출력 방식이 ToolName과 다르면 읽을 때 실패한다. 프롬프트가 서버에 없는 도구를 약속하지 않게 한다.
 */
public class ToolCatalog {

    public record Tool(String name, String metric, String description, List<String> args, List<String> outputs) {
    }

    private final List<Tool> tools;

    @SuppressWarnings("unchecked")
    public ToolCatalog(Path file) {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read tool catalog " + file.toAbsolutePath().normalize(), e);
        }
        if (root == null || !(root.get("tools") instanceof List<?> entries)) {
            throw new IllegalStateException("tools.yml must have a tools list");
        }
        this.tools = ((List<Map<String, Object>>) entries).stream()
                .map(entry -> new Tool(text(entry, "name"), text(entry, "metric"), text(entry, "description"),
                        strings(entry, "args"), strings(entry, "outputs")))
                .toList();
        requireMatchesToolNames();
    }

    public List<Tool> tools() {
        return tools;
    }

    public String render() {
        return tools.stream()
                .map(tool -> "- " + tool.name() + " (" + tool.metric() + "): " + tool.description()
                        + " args: " + String.join(", ", tool.args()) + "."
                        + " output: " + String.join(" | ", tool.outputs()) + ".")
                .collect(Collectors.joining("\n"));
    }

    private void requireMatchesToolNames() {
        Set<String> expected = Arrays.stream(ToolName.values()).map(ToolName::value).collect(Collectors.toSet());
        Set<String> actual = tools.stream().map(Tool::name).collect(Collectors.toSet());
        if (!expected.equals(actual) || tools.size() != expected.size()) {
            throw new IllegalStateException("tools.yml tools " + actual + " differ from the server tools " + expected);
        }
        for (Tool tool : tools) {
            Set<String> outputs = ToolName.fromValue(tool.name()).orElseThrow().outputs().stream()
                    .map(ToolOutput::value)
                    .collect(Collectors.toSet());
            if (!outputs.equals(Set.copyOf(tool.outputs()))) {
                throw new IllegalStateException("tools.yml outputs of " + tool.name() + " " + tool.outputs()
                        + " differ from the server outputs " + outputs);
            }
        }
    }

    private static String text(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("tools.yml entry is missing " + key + ": " + entry);
        }
        return value.toString().strip();
    }

    private static List<String> strings(Map<String, Object> entry, String key) {
        if (!(entry.get(key) instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException("tools.yml entry is missing " + key + ": " + entry);
        }
        return values.stream().map(String::valueOf).toList();
    }
}
