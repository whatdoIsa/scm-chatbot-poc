package com.scmchat.prompt;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * prompts/metrics.yml의 기간 규칙, 단위 규칙, 용어, 지표 정의를 프롬프트에 넣을 문장으로 만든다(API_SPEC 4.2).
 * pruned=false면 전체, true면 질문에 키워드가 들어 있는 용어와 지표만 고른다. 기간 규칙과 단위 규칙은 항상 넣는다.
 */
public class MetricsDictionary {

    public record DateRule(String expr, String range) {
    }

    public record Term(List<String> words, String maps) {
    }

    public record Metric(String name, List<String> keywords, String definition, List<String> tables) {
    }

    private final LocalDate asOfDate;
    private final List<DateRule> dateRules;
    private final List<String> unitRules;
    private final List<Term> terms;
    private final List<Metric> metrics;

    @SuppressWarnings("unchecked")
    public MetricsDictionary(Path file) {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read metrics dictionary " + file.toAbsolutePath().normalize(), e);
        }
        this.asOfDate = toLocalDate(required(root, "asOfDate"));
        this.dateRules = ((List<Map<String, Object>>) required(root, "dateRules")).stream()
                .map(rule -> new DateRule(text(rule, "expr"), text(rule, "range")))
                .toList();
        this.unitRules = ((List<Object>) required(root, "unitRules")).stream().map(String::valueOf).toList();
        this.terms = ((List<Map<String, Object>>) required(root, "terms")).stream()
                .map(term -> new Term(strings(term, "words"), text(term, "maps")))
                .toList();
        this.metrics = ((List<Map<String, Object>>) required(root, "metrics")).stream()
                .map(metric -> new Metric(text(metric, "name"), strings(metric, "keywords"),
                        text(metric, "definition"), strings(metric, "tables")))
                .toList();
    }

    public LocalDate asOfDate() {
        return asOfDate;
    }

    public String dateRules() {
        StringBuilder out = new StringBuilder();
        for (DateRule rule : dateRules) {
            out.append("- ").append(rule.expr()).append(": ").append(rule.range()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    public String select(String question, boolean pruned) {
        List<Term> selectedTerms = pruned ? terms.stream().filter(term -> mentions(question, term.words())).toList()
                : terms;
        List<Metric> selectedMetrics = selectMetrics(question, pruned);

        StringBuilder out = new StringBuilder("Unit rules:\n");
        unitRules.forEach(rule -> out.append("- ").append(rule).append('\n'));
        if (!selectedTerms.isEmpty()) {
            out.append("Terms:\n");
            selectedTerms.forEach(term -> out.append("- ").append(String.join(", ", term.words()))
                    .append(" -> ").append(term.maps()).append('\n'));
        }
        if (!selectedMetrics.isEmpty()) {
            out.append("Metrics:\n");
            selectedMetrics.forEach(metric -> out.append("- ").append(metric.name()).append(": ")
                    .append(metric.definition()).append('\n'));
        }
        return out.toString().stripTrailing();
    }

    /** 질문에 키워드가 들어 있는 지표가 쓰는 테이블. 스키마 축소에서 지표에 필요한 테이블을 빠뜨리지 않게 한다. */
    public Set<String> tablesFor(String question) {
        Set<String> tables = new LinkedHashSet<>();
        selectMetrics(question, true).forEach(metric -> tables.addAll(metric.tables()));
        return tables;
    }

    private List<Metric> selectMetrics(String question, boolean pruned) {
        return pruned ? metrics.stream().filter(metric -> mentions(question, metric.keywords())).toList() : metrics;
    }

    static boolean mentions(String question, List<String> keywords) {
        String normalized = normalize(question);
        return keywords.stream().anyMatch(keyword -> normalized.contains(normalize(keyword)));
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    // SnakeYAML은 따옴표 없는 날짜를 UTC 자정의 java.util.Date로 읽는다.
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static Object required(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            throw new IllegalStateException("metrics.yml is missing " + key);
        }
        return value;
    }

    private static String text(Map<String, Object> map, String key) {
        return String.valueOf(required(map, key));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> map, String key) {
        List<String> values = new ArrayList<>();
        ((List<Object>) required(map, key)).forEach(value -> values.add(String.valueOf(value)));
        return values;
    }
}
