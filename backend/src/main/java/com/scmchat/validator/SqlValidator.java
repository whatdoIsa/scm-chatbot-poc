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
 * 리터럴 경계를 PostgreSQL과 다르게 읽을 수 있는 표기(달러 인용, 역슬래시, E''·U& 접두 문자열)는 처음부터 거부한다.
 * 함수는 허용 목록에 있는 것만 통과시킨다.
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
            "\\b(pg_\\w*|set_config|current_setting|dblink\\w*|lo_\\w+|copy|information_schema|\\w+_to_xml\\w*|"
                    + "current_user|session_user|current_role|current_database|current_catalog|current_schema\\w*)\\b");
    private static final Pattern AMBIGUOUS_STRING = Pattern.compile("[$\\\\]|\\b(e|b|x|n|u&)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL = Pattern.compile("([\\p{L}\\p{N}_]+)\\s*\\(");
    private static final Set<String> ALLOWED_BEFORE_PAREN = Set.of(
            // 괄호 앞에 올 수 있는 SQL 키워드와 타입
            "select", "from", "where", "and", "or", "not", "in", "exists", "on", "as", "over", "filter", "by",
            "when", "then", "else", "case", "having", "with", "union", "all", "any", "some", "values", "join",
            "using", "within", "group", "partition", "order", "distinct", "interval", "date", "numeric", "decimal",
            "varchar", "char", "limit", "offset", "lateral", "array", "row", "between", "is", "like", "end",
            "except", "intersect", "timestamp", "integer", "int", "bigint", "smallint", "text", "float", "real",
            "double", "precision", "boolean",
            // 집계, 윈도, 수치, 날짜, 문자열 함수
            "sum", "count", "avg", "min", "max", "abs", "round", "ceil", "ceiling", "floor", "trunc", "sign",
            "power", "sqrt", "mod", "div", "exp", "ln", "log", "coalesce", "nullif", "greatest", "least", "cast",
            "extract", "date_trunc", "date_part", "to_char", "to_date", "make_date", "age",
            "rank", "dense_rank", "row_number", "lag", "lead", "first_value", "last_value", "ntile",
            "percent_rank", "cume_dist", "percentile_cont", "percentile_disc", "mode",
            "stddev", "stddev_samp", "stddev_pop", "variance", "var_samp", "var_pop",
            "string_agg", "array_agg", "bool_and", "bool_or", "every",
            "upper", "lower", "length", "concat", "substring", "substr", "left", "right", "trim", "ltrim",
            "rtrim", "replace", "position");

    private final int limit;
    private final Set<String> allowedTables = Set.copyOf(SchemaCatalog.tables());

    public SqlValidator(ChatProperties chatProperties) {
        this.limit = chatProperties.validatorLimit();
    }

    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.rejected(sql, "SQL is empty");
        }
        if (AMBIGUOUS_STRING.matcher(sql).find()) {
            return ValidationResult.rejected(sql, "dollar quotes, backslashes, and prefixed strings are not allowed");
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

        String forbiddenCall = firstForbiddenCall(scanned);
        if (forbiddenCall != null) {
            return ValidationResult.rejected(sql, "function not allowed: " + forbiddenCall);
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
        try {
            boolean limitApplied = applyLimit(select);
            return ValidationResult.accepted(select.toString(), limitApplied);
        } catch (RuntimeException e) {
            return ValidationResult.rejected(sql, "LIMIT cannot be applied");
        }
    }

    /** 따옴표로 감싼 식별자도 함수 이름으로 쓰일 수 있어 따옴표를 떼고 본다. 테이블 별칭 뒤의 괄호는 CTE 컬럼 목록 정도라 드물다. */
    private static String firstForbiddenCall(String scanned) {
        Matcher matcher = CALL.matcher(scanned.replace("\"", ""));
        while (matcher.find()) {
            if (!ALLOWED_BEFORE_PAREN.contains(matcher.group(1))) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private boolean isAllowedTable(String name) {
        String normalized = name.replace("\"", "").toLowerCase(Locale.ROOT);
        if (normalized.startsWith("scm.")) {
            normalized = normalized.substring("scm.".length());
        }
        return allowedTables.contains(normalized);
    }

    /**
     * 최상위 LIMIT이 없거나 상한보다 크면 상한으로 맞추고 true를 돌려준다.
     * 숫자가 아닌 LIMIT(ALL, 식)과 long 범위를 넘는 숫자도 상한으로 바꾼다.
     */
    private boolean applyLimit(Select select) {
        Limit current = select.getLimit();
        boolean withinLimit = current != null
                && current.getRowCount() instanceof LongValue rowCount
                && isWithinLimit(rowCount);
        if (!withinLimit) {
            Limit capped = current == null ? new Limit() : current;
            capped.setRowCount(new LongValue(limit));
            select.setLimit(capped);
        }
        if (select.getFetch() != null) {
            select.setFetch(null);
        }
        return !withinLimit;
    }

    private boolean isWithinLimit(LongValue rowCount) {
        try {
            return rowCount.getValue() >= 0 && rowCount.getValue() <= limit;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }
}
