package com.scmchat.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.scmchat.llm.Action;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * eval/golden_set.json을 읽고 문항 하나하나의 형식을 검증한다(API_SPEC 4.5).
 * 문항 수와 유형별 개수, 정답 SQL의 실행 가능 여부는 GoldenSetIT가 확인한다.
 */
@Component
public class GoldenSetLoader {

    private static final Pattern ID = Pattern.compile("Q\\d{2}");
    private static final Set<String> FIELDS =
            Set.of("id", "type", "question", "expectedAction", "goldenSql", "orderSensitive", "note");

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public List<GoldenItem> load(Path file) {
        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new GoldenSetException("cannot read golden set " + file.toAbsolutePath().normalize(), e);
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new GoldenSetException("golden set must be a non-empty JSON array");
        }

        List<GoldenItem> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> questions = new HashSet<>();
        for (JsonNode node : root) {
            GoldenItem item = toItem(node);
            if (!ids.add(item.id())) {
                throw new GoldenSetException("duplicate id: " + item.id());
            }
            if (!questions.add(item.question())) {
                throw new GoldenSetException(item.id() + ": duplicate question");
            }
            items.add(item);
        }
        return List.copyOf(items);
    }

    private GoldenItem toItem(JsonNode node) {
        if (!node.isObject()) {
            throw new GoldenSetException("every golden item must be a JSON object");
        }
        String id = requiredText(node, "id", "?");
        if (!ID.matcher(id).matches()) {
            throw new GoldenSetException(id + ": id must look like Q01");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new GoldenSetException(id + ": unknown field " + field);
            }
        });

        QuestionType type = parseType(id, requiredText(node, "type", id));
        String question = requiredText(node, "question", id);
        String actionValue = requiredText(node, "expectedAction", id);
        Action expectedAction = Action.fromValue(actionValue)
                .orElseThrow(() -> new GoldenSetException(id + ": unknown expectedAction " + actionValue));
        String goldenSql = optionalText(node, "goldenSql", id);
        JsonNode orderSensitiveNode = node.get("orderSensitive");
        if (orderSensitiveNode == null || !orderSensitiveNode.isBoolean()) {
            throw new GoldenSetException(id + ": orderSensitive must be true or false");
        }
        boolean orderSensitive = orderSensitiveNode.booleanValue();

        if (expectedAction == Action.SQL && goldenSql == null) {
            throw new GoldenSetException(id + ": goldenSql is required when expectedAction is sql");
        }
        if (expectedAction != Action.SQL && goldenSql != null) {
            throw new GoldenSetException(id + ": goldenSql is only allowed when expectedAction is sql");
        }
        requireActionMatchesType(id, type, expectedAction);
        if (type == QuestionType.RANKING && !orderSensitive) {
            throw new GoldenSetException(id + ": RANKING items must be orderSensitive");
        }
        return new GoldenItem(id, type, question, expectedAction, goldenSql, orderSensitive,
                optionalText(node, "note", id));
    }

    private static void requireActionMatchesType(String id, QuestionType type, Action expectedAction) {
        Action required = switch (type) {
            case AMBIGUOUS -> Action.CLARIFY;
            case REFUSE -> Action.REFUSE;
            default -> Action.SQL;
        };
        if (expectedAction != required) {
            throw new GoldenSetException(
                    id + ": type " + type + " requires expectedAction " + required.value());
        }
    }

    private static QuestionType parseType(String id, String value) {
        try {
            return QuestionType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new GoldenSetException(id + ": unknown type " + value, e);
        }
    }

    private static String requiredText(JsonNode node, String field, String id) {
        String value = optionalText(node, field, id);
        if (value == null) {
            throw new GoldenSetException(id + ": " + field + " is required");
        }
        return value;
    }

    /** 없는 필드, null, 빈 문자열은 null로 본다. */
    private static String optionalText(JsonNode node, String field, String id) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new GoldenSetException(id + ": " + field + " must be a string");
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
