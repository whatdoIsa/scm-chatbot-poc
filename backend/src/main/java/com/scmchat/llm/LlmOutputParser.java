package com.scmchat.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** LLM 원문을 API_SPEC 3.2 규칙으로 해석한다. 규칙을 어기면 {@link FormatException}을 던진다. */
@Component
public class LlmOutputParser {

    private static final Pattern CODE_FENCE = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.DOTALL);
    private static final Pattern TRAILING_SEMICOLONS = Pattern.compile("[;\\s]+$");

    // JSON 뒤에 부가 텍스트가 붙으면 실패하도록 한다.
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ParsedOutput parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new FormatException("LLM output is empty");
        }
        JsonNode root = readObject(stripCodeFence(raw.strip()));

        String actionValue = text(root, "action");
        Action action = Action.fromValue(actionValue)
                .orElseThrow(() -> new FormatException("unknown action: " + actionValue));
        String sql = stripTrailingSemicolons(text(root, "sql"));
        String message = text(root, "message");

        if (action == Action.SQL && sql == null) {
            throw new FormatException("action is sql but sql is empty");
        }
        if (action != Action.SQL && message == null) {
            throw new FormatException("action is " + action.value() + " but message is empty");
        }
        return new ParsedOutput(action, sql, message);
    }

    private JsonNode readObject(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new FormatException("LLM output is not a single JSON object: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new FormatException("LLM output is not a JSON object");
        }
        return root;
    }

    private static String stripCodeFence(String text) {
        Matcher matcher = CODE_FENCE.matcher(text);
        return matcher.matches() ? matcher.group(1) : text;
    }

    /** 없는 필드, null, 빈 문자열은 모두 null로 본다. */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new FormatException(field + " must be a string");
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    private static String stripTrailingSemicolons(String sql) {
        if (sql == null) {
            return null;
        }
        String stripped = TRAILING_SEMICOLONS.matcher(sql).replaceFirst("");
        return stripped.isEmpty() ? null : stripped;
    }
}
