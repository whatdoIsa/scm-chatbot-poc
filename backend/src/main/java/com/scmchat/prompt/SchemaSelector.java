package com.scmchat.prompt;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * prompts/schema_rules.yml의 키워드 규칙으로 질문에 필요한 테이블을 고른다(API_SPEC 4.4).
 * 어떤 규칙에도 맞지 않으면 전체 6개를 돌려주고, 팩트 테이블이 하나라도 있으면 product와 location을 함께 넣는다.
 */
public class SchemaSelector {

    private record Rule(List<String> keywords, List<String> tables) {
    }

    private final List<Rule> rules = new ArrayList<>();
    private final boolean fallbackAllTables;
    private final boolean alwaysAddDimensions;

    @SuppressWarnings("unchecked")
    public SchemaSelector(Path file) {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read schema rules " + file.toAbsolutePath().normalize(), e);
        }
        for (Map<String, Object> rule : (List<Map<String, Object>>) root.get("rules")) {
            List<String> keywords = ((List<Object>) rule.get("keywords")).stream().map(String::valueOf).toList();
            List<String> tables = ((List<Object>) rule.get("tables")).stream().map(String::valueOf).toList();
            SchemaCatalog.ddlOf(tables);
            rules.add(new Rule(keywords, tables));
        }
        this.fallbackAllTables = Boolean.TRUE.equals(root.get("fallbackAllTables"));
        this.alwaysAddDimensions = Boolean.TRUE.equals(root.get("alwaysAddDimensions"));
    }

    public Set<String> select(String question) {
        Set<String> selected = new LinkedHashSet<>();
        for (Rule rule : rules) {
            if (MetricsDictionary.mentions(question, rule.keywords())) {
                selected.addAll(rule.tables());
            }
        }
        if (selected.isEmpty() && fallbackAllTables) {
            return new LinkedHashSet<>(SchemaCatalog.tables());
        }
        if (alwaysAddDimensions && selected.stream().anyMatch(SchemaCatalog::isFactTable)) {
            selected.add("product");
            selected.add("location");
        }
        return selected;
    }
}
