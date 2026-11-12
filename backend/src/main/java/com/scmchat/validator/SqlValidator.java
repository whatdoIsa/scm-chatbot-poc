package com.scmchat.validator;

import com.scmchat.config.ChatProperties;
import com.scmchat.prompt.SchemaCatalog;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * v2에서 LLM이 만든 SQL을 실행 전에 검사한다(API_SPEC 6장 SqlValidator 규칙).
 * 파서로 구조를 확인하고, 파서가 놓칠 수 있는 구문은 문자열 리터럴을 지운 원문에서 한 번 더 확인한다.
 * 실제 권한 차단은 scm_ro 계정과 읽기 전용 실행기가 맡고, 이 검사는 그 앞의 한 겹이다.
 */
@Component
public class SqlValidator {

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern COMMENT = Pattern.compile("--|/\\*|\\*/");
    private static final Pattern WRITE_KEYWORD = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|merge|call|copy|vacuum|reset|"
                    + "set|do|lock|listen|notify|execute|prepare|refresh|reindex|cluster|comment|security)\\b");
    private static final Pattern INTO = Pattern.compile("\\binto\\b");
    private static final Pattern ROW_LOCK = Pattern.compile("\\bfor\\s+(update|share|no\\s+key\\s+update|key\\s+share)\\b");
    private static final Pattern FORBIDDEN_NAME = Pattern.compile(
            "\\b(pg_\\w*|set_config|current_setting|dblink\\w*|lo_\\w+|copy|information_schema|\\w+_to_xml\\w*)\\b");

    private final int limit;
    private final Set<String> allowedTables = Set.copyOf(SchemaCatalog.tables());

    public SqlValidator(ChatProperties chatProperties) {
        this.limit = chatProperties.validatorLimit();
    }

    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.rejected(sql, "SQL is empty");
        }
        String scanned = STRING_LITERAL.matcher(sql).replaceAll("''").toLowerCase(Locale.ROOT);
        if (COMMENT.matcher(scanned).find()) {
            return ValidationResult.rejected(sql, "comments are not allowed");
        }
        if (scanned.contains(";")) {
            return ValidationResult.rejected(sql, "only one statement is allowed");
        }
        String rejectedToken = firstMatch(WRITE_KEYWORD, scanned);
        if (rejectedToken != null) {
            return ValidationResult.rejected(sql, "keyword not allowed: " + rejectedToken);
        }
        if (INTO.matcher(scanned).find()) {
            return ValidationResult.rejected(sql, "SELECT INTO is not allowed");
        }
        if (ROW_LOCK.matcher(scanned).find()) {
            return ValidationResult.rejected(sql, "FOR UPDATE/SHARE is not allowed");
        }
        String forbiddenName = firstMatch(FORBIDDEN_NAME, scanned);
        if (forbiddenName != null) {
            return ValidationResult.rejected(sql, "name not allowed: " + forbiddenName);
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException | RuntimeException e) {
            return ValidationResult.rejected(sql, "SQL cannot be parsed");
        }
        List<Statement> parsed = statements == null ? List.of() : statements;
        if (parsed.size() != 1) {
            return ValidationResult.rejected(sql, "only one statement is allowed");
        }
        if (!(parsed.get(0) instanceof Select select)) {
            return ValidationResult.rejected(sql, "only SELECT is allowed");
        }

        Set<String> tables;
        try {
            tables = new TablesNamesFinder<>().getTables((Statement) select);
        } catch (RuntimeException e) {
            return ValidationResult.rejected(sql, "tables cannot be determined");
        }
        for (String table : tables) {
            if (!isAllowedTable(table)) {
                return ValidationResult.rejected(sql, "table not allowed: " + table);
            }
        }
        return ValidationResult.accepted(applyLimit(select));
    }

    private boolean isAllowedTable(String name) {
        String normalized = name.replace("\"", "").toLowerCase(Locale.ROOT);
        if (normalized.startsWith("scm.")) {
            normalized = normalized.substring("scm.".length());
        }
        return allowedTables.contains(normalized);
    }

    /** 최상위 LIMIT이 없거나 상한보다 크면 상한으로 맞춘다. 숫자가 아닌 LIMIT(ALL, 식)도 상한으로 바꾼다. */
    private String applyLimit(Select select) {
        Limit current = select.getLimit();
        boolean withinLimit = current != null
                && current.getRowCount() instanceof LongValue rowCount
                && rowCount.getValue() >= 0
                && rowCount.getValue() <= limit;
        if (!withinLimit) {
            Limit capped = current == null ? new Limit() : current;
            capped.setRowCount(new LongValue(limit));
            select.setLimit(capped);
        }
        if (select.getFetch() != null) {
            select.setFetch(null);
        }
        return select.toString();
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }
}
