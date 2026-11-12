package com.scmchat.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@SpringBootTest
class ReadOnlySqlExecutorIT {

    private static final String READ_ONLY_TRANSACTION = "25006";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String QUERY_CANCELED = "57014";

    @Autowired
    private ReadOnlySqlExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsColumnLabelsAndRows() {
        QueryResult result = executor.execute(
                "SELECT sku, abc_class AS grade FROM product WHERE sku IN ('SKU-001', 'SKU-002') ORDER BY sku");

        assertThat(result.columns()).containsExactly("sku", "grade");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).get(0)).isEqualTo("SKU-001");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void convertsValuesForJson() {
        QueryResult result = executor.execute("""
                SELECT max(sale_date) AS last_day, sum(qty) AS total, avg(qty) AS average,
                       NULL::integer AS missing, true AS flag
                FROM sales_daily WHERE sku = 'SKU-007'
                """);

        List<Object> row = result.rows().get(0);
        assertThat(row.get(0)).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(row.get(1)).isInstanceOf(Long.class);
        assertThat(row.get(2)).isInstanceOf(BigDecimal.class);
        assertThat(row.get(3)).isNull();
        assertThat(row.get(4)).isEqualTo(true);
    }

    @Test
    void truncatesAtMaxRows() {
        QueryResult result = executor.execute("SELECT * FROM sales_daily");

        assertThat(result.rows()).hasSize(1000);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void doesNotMarkExactlyMaxRowsAsTruncated() {
        QueryResult result = executor.execute("SELECT * FROM sales_daily LIMIT 1000");

        assertThat(result.rows()).hasSize(1000);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void reportsPostgresErrorForInvalidSql() {
        SqlExecutionException thrown = failureOf("SELECT stock FROM inventory");

        assertThat(thrown.getSqlState()).isEqualTo("42P01");
        assertThat(thrown.getMessage()).contains("relation \"inventory\" does not exist");
    }

    @Test
    void rejectsWrites() {
        assertThat(failureOf("UPDATE product SET unit_cost = 0").getSqlState()).isEqualTo(READ_ONLY_TRANSACTION);
        assertThat(failureOf("DROP TABLE sales_daily").getSqlState()).isEqualTo(READ_ONLY_TRANSACTION);
    }

    @Test
    void rejectsWritesEvenWhenSqlTurnsReadOnlyOff() {
        long before = countSalesRows();

        SqlExecutionException sessionDefault =
                failureOf("SET default_transaction_read_only = off; DELETE FROM sales_daily");
        SqlExecutionException transactionMode = failureOf("SET TRANSACTION READ WRITE; DELETE FROM sales_daily");

        assertThat(sessionDefault.getSqlState()).isIn(READ_ONLY_TRANSACTION, INSUFFICIENT_PRIVILEGE);
        assertThat(transactionMode.getSqlState()).isIn(READ_ONLY_TRANSACTION, INSUFFICIENT_PRIVILEGE);
        assertThat(countSalesRows()).isEqualTo(before);
    }

    @Test
    void cancelsQueriesLongerThanFiveSeconds() {
        long startedAt = System.nanoTime();

        SqlExecutionException thrown = failureOf("SELECT pg_sleep(10)");

        assertThat(thrown.getSqlState()).isEqualTo(QUERY_CANCELED);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void cancelsLongQueriesEvenWhenSqlDisablesStatementTimeout() {
        long startedAt = System.nanoTime();

        SqlExecutionException thrown = failureOf("SET statement_timeout = 0; SELECT pg_sleep(10)");

        assertThat(thrown.getSqlState()).isEqualTo(QUERY_CANCELED);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void doesNotLeaveSessionSettingsOnPooledConnections() {
        executor.execute("SET statement_timeout = 0; SET default_transaction_read_only = off; SELECT 1");

        // 풀의 모든 커넥션을 거치도록 풀 크기보다 많이 실행한다.
        for (int i = 0; i < 8; i++) {
            assertThat(executor.execute("SHOW statement_timeout").rows().get(0).get(0)).isEqualTo("5s");
            assertThat(executor.execute("SHOW default_transaction_read_only").rows().get(0).get(0)).isEqualTo("on");
        }
    }

    @Test
    void failsWhenStatementReturnsNoResultSet() {
        SqlExecutionException thrown = failureOf("SET search_path = scm");

        assertThat(thrown.getMessage()).contains("did not return a result set");
    }

    private SqlExecutionException failureOf(String sql) {
        SqlExecutionException thrown = catchThrowableOfType(SqlExecutionException.class, () -> executor.execute(sql));
        assertThat(thrown).as("expected failure for: %s", sql).isNotNull();
        return thrown;
    }

    private long countSalesRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM scm.sales_daily", Long.class);
    }
}
