package com.scmchat.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.config.ChatProperties;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlValidatorTest {

    private final SqlValidator validator = new SqlValidator(new ChatProperties(
            "v2", LocalDate.of(2026, 11, 8), LocalDate.of(2026, 8, 10), 1000, 200, 20, "../prompts",
            new ChatProperties.Llm("fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120)));

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT on_hand FROM inventory_daily WHERE sku = 'SKU-007' AND snap_date = DATE '2026-11-08'",
            "SELECT p.category, SUM(s.qty) FROM sales_daily s JOIN product p ON p.sku = s.sku GROUP BY p.category",
            "WITH d AS (SELECT sku, SUM(qty) AS q FROM sales_daily GROUP BY sku) SELECT sku FROM d ORDER BY q DESC",
            "SELECT sku, RANK() OVER (ORDER BY SUM(qty) DESC) AS r FROM sales_daily GROUP BY sku",
            "SELECT SUM(on_hand) FROM scm.inventory_daily WHERE sku = 'SKU-007'",
            "SELECT sku FROM product WHERE name = 'delete; drop -- /* update */ pg_sleep'",
            "SELECT s.sku FROM sales_daily s WHERE EXISTS (SELECT 1 FROM supply_policy p WHERE p.sku = s.sku)",
            "SELECT region FROM location UNION SELECT category FROM product",
            "SELECT 1 - SUM(ABS(f.forecast_qty - 10))::numeric / NULLIF(SUM(f.forecast_qty), 0) FROM forecast_weekly f",
            "SELECT sku, SUM(qty) AS \"판매량\" FROM sales_daily WHERE sale_date >= DATE '2026-11-02' GROUP BY sku",
            "SELECT EXTRACT(ISODOW FROM sale_date) AS dow, AVG(qty) FROM sales_daily GROUP BY 1 ORDER BY 1",
            "SELECT date_trunc('week', sale_date)::date AS wk, SUM(qty) FILTER (WHERE qty > 0) FROM sales_daily GROUP BY 1",
            "SELECT sku FROM product WHERE sku IN (SELECT sku FROM supply_policy WHERE lead_time_days = 7)",
            "SELECT COALESCE(SUM(qty), 0)::numeric / 28 FROM sales_daily WHERE sku = 'It''s'",
            "SELECT sku, LAG(qty) OVER (PARTITION BY sku ORDER BY sale_date) FROM sales_daily WHERE location_id = 'LOC-01'"
    })
    void acceptsReadOnlySelects(String sql) {
        ValidationResult result = validator.validate(sql);

        assertThat(result.ok()).as(String.valueOf(result.reason())).isTrue();
        assertThat(result.reason()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO location VALUES ('LOC-99', 'x', 'x')",
            "UPDATE inventory_daily SET on_hand = 0 WHERE sku = 'SKU-007'",
            "DELETE FROM supply_policy",
            "DROP TABLE sales_daily",
            "TRUNCATE sales_daily",
            "SELECT * FROM product; DROP TABLE product",
            "SELECT * FROM product; SELECT * FROM location",
            "WITH d AS (DELETE FROM sales_daily RETURNING *) SELECT * FROM d",
            "SELECT * FROM pg_user",
            "SELECT * FROM pg_catalog.pg_tables",
            "SELECT * FROM information_schema.tables",
            "SELECT * FROM app.chat_log",
            "SELECT * FROM public.product",
            "SELECT pg_sleep(10)",
            "SELECT pg_read_file('/etc/passwd')",
            "SELECT set_config('statement_timeout', '0', false)",
            "SELECT current_setting('server_version')",
            "SELECT * FROM dblink('host=x', 'select 1') AS t(a int)",
            "SELECT lo_import('/etc/passwd')",
            "SELECT query_to_xml('select * from app.chat_log', true, true, '')",
            "SELECT * FROM product FOR UPDATE",
            "SELECT * FROM product FOR SHARE",
            "SELECT * INTO backup_product FROM product",
            "SELECT 1 /* ; DROP TABLE product */",
            "SELECT 1 -- DROP TABLE product",
            "SELECT * FROM orders",
            "SELECT * FROM \"pg_user\"",
            "SET statement_timeout = 0",
            "EXPLAIN ANALYZE SELECT * FROM product",
            "COPY product TO '/tmp/x'",
            "SELECT * FROM product WHERE",
            "SELECT E'\\'' || (SELECT string_agg(rolname, ',') FROM pg_roles) --'",
            "SELECT $$'$$ || pg_sleep(4) || $$'$$",
            "SELECT $q$x$q$ FROM product",
            "SELECT U&\"\\0070g_sleep\"(4)",
            "SELECT repeat('x', 900000000)",
            "SELECT version()",
            "SELECT current_user",
            "SELECT inet_server_addr()",
            "SELECT has_table_privilege('scm_ro', 'app.chat_log', 'SELECT')",
            "SELECT * FROM generate_series(1, 100000000)",
            "SELECT nextval('app.chat_log_id_seq')",
            "",
            "   "
    })
    void rejectsWritesMultipleStatementsAndForbiddenObjects(String sql) {
        ValidationResult result = validator.validate(sql);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isNotBlank();
        assertThat(result.sql()).isEqualTo(sql);
    }

    @Test
    void reportsWhetherItAppliedTheLimit() {
        assertThat(validator.validate("SELECT sku FROM product").limitApplied()).isTrue();
        assertThat(validator.validate("SELECT sku FROM product LIMIT 500").limitApplied()).isTrue();
        assertThat(validator.validate("SELECT sku FROM product LIMIT 50").limitApplied()).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(validator.validate(null).ok()).isFalse();
    }

    @Test
    void addsLimitWhenMissing() {
        assertThat(validator.validate("SELECT sku FROM product").sql()).isEqualTo("SELECT sku FROM product LIMIT 200");
    }

    @Test
    void lowersLimitAboveCap() {
        assertThat(validator.validate("SELECT sku FROM product LIMIT 500").sql())
                .isEqualTo("SELECT sku FROM product LIMIT 200");
    }

    @Test
    void keepsLimitWithinCap() {
        assertThat(validator.validate("SELECT sku FROM product ORDER BY sku LIMIT 50").sql())
                .isEqualTo("SELECT sku FROM product ORDER BY sku LIMIT 50");
    }

    @Test
    void replacesLimitAll() {
        assertThat(validator.validate("SELECT sku FROM product LIMIT ALL").sql())
                .isEqualTo("SELECT sku FROM product LIMIT 200");
    }

    @Test
    void capsTopLevelLimitOfCteQueryWithoutTouchingInnerLimit() {
        String sql = validator.validate(
                "WITH d AS (SELECT sku FROM product ORDER BY sku LIMIT 5) SELECT sku FROM d").sql();

        assertThat(sql).contains("LIMIT 5)").endsWith("LIMIT 200");
    }

    @Test
    void capsLimitThatOverflowsLong() {
        ValidationResult result = validator.validate("SELECT sku FROM product LIMIT 99999999999999999999");

        assertThat(result.ok() ? result.sql() : "rejected").isIn("rejected", "SELECT sku FROM product LIMIT 200");
        assertThat(result.reason() == null || !result.reason().isBlank()).isTrue();
    }

    @Test
    void capsLimitOfUnion() {
        assertThat(validator.validate("SELECT region FROM location UNION SELECT category FROM product").sql())
                .endsWith("LIMIT 200");
    }
}
