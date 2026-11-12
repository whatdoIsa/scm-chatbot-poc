package com.scmchat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * scm_ro 계정의 방어 계층을 확인한다. 기본 상태에서는 읽기 전용 트랜잭션이 쓰기를 막고,
 * 세션에서 읽기 전용을 꺼도 권한이 없어 거부되어야 한다.
 */
@Tag("integration")
@SpringBootTest
class ReadOnlyRoleIT {

    private static final String READ_ONLY_TRANSACTION = "25006";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String QUERY_CANCELED = "57014";

    private static final String INSERT = "INSERT INTO scm.location VALUES ('LOC-99', 'x', 'x')";
    private static final String UPDATE = "UPDATE scm.product SET unit_cost = 0";
    private static final String DELETE = "DELETE FROM scm.sales_daily";
    private static final String DDL = "CREATE TABLE scm.x (a int)";

    @Autowired
    @Qualifier("scmRoJdbcTemplate")
    private JdbcTemplate scmRo;

    @Test
    void selectsScmTablesWithoutSchemaPrefix() {
        Integer products = scmRo.queryForObject("SELECT count(*) FROM product", Integer.class);

        assertThat(products).isEqualTo(50);
    }

    @ParameterizedTest
    @ValueSource(strings = {INSERT, UPDATE, DELETE, DDL})
    void rejectsWritesInDefaultReadOnlyTransaction(String sql) {
        assertThat(sqlStateOf(() -> scmRo.execute(sql))).isEqualTo(READ_ONLY_TRANSACTION);
    }

    @ParameterizedTest
    @ValueSource(strings = {INSERT, UPDATE, DELETE, DDL, "CREATE TEMP TABLE t (a int)"})
    void rejectsWritesByPrivilegeEvenWhenReadOnlyIsTurnedOff(String sql) {
        assertThat(sqlStateWithReadOnlyOff(sql)).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void cannotReadAppSchema() {
        assertThat(sqlStateOf(() -> scmRo.queryForList("SELECT * FROM app.chat_log")))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    void cancelsStatementsLongerThanFiveSeconds() {
        long startedAt = System.nanoTime();

        String sqlState = sqlStateOf(() -> scmRo.queryForList("SELECT pg_sleep(10)"));

        assertThat(sqlState).isEqualTo(QUERY_CANCELED);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(8));
    }

    private static String sqlStateOf(Runnable statement) {
        Throwable thrown = catchThrowable(statement::run);
        assertThat(thrown).isInstanceOf(DataAccessException.class);
        Throwable root = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(root).isInstanceOf(SQLException.class);
        return ((SQLException) root).getSQLState();
    }

    // 풀에 돌아가는 커넥션에 세션 설정이 남지 않도록 같은 커넥션에서 RESET까지 실행한다.
    private String sqlStateWithReadOnlyOff(String sql) {
        return scmRo.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET default_transaction_read_only = off");
                try {
                    statement.execute(sql);
                    return "00000";
                } catch (SQLException e) {
                    return e.getSQLState();
                } finally {
                    statement.execute("RESET default_transaction_read_only");
                }
            }
        });
    }
}
