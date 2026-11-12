package com.scmchat.executor;

import com.scmchat.config.ChatProperties;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * LLM이 만든 SQL을 scm_ro 계정으로 실행하는 유일한 경로다.
 * 읽기 전용 트랜잭션 안에서 실행하고 항상 롤백하므로, SQL에 SET이 섞여 있어도 풀의 커넥션에 설정이 남지 않는다.
 */
@Component
public class ReadOnlySqlExecutor {

    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final JdbcTemplate scmRoJdbcTemplate;
    private final int maxRows;

    public ReadOnlySqlExecutor(@Qualifier("scmRoJdbcTemplate") JdbcTemplate scmRoJdbcTemplate,
            ChatProperties chatProperties) {
        this.scmRoJdbcTemplate = scmRoJdbcTemplate;
        this.maxRows = chatProperties.maxRows();
    }

    public QueryResult execute(String sql) {
        try {
            return scmRoJdbcTemplate.execute(
                    (ConnectionCallback<QueryResult>) connection -> executeReadOnly(connection, sql));
        } catch (DataAccessException e) {
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            String sqlState = root instanceof SQLException sqlException ? sqlException.getSQLState() : null;
            throw new SqlExecutionException(root.getMessage(), sqlState, e);
        }
    }

    private QueryResult executeReadOnly(Connection connection, String sql) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        boolean previousReadOnly = connection.isReadOnly();
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            // 서버의 statement_timeout과 별개로 드라이버가 5초 뒤 취소를 보낸다. SQL이 서버 설정을 바꿔도 적용된다.
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            // 잘렸는지 알기 위해 한 행을 더 받는다.
            statement.setMaxRows(maxRows + 1);

            boolean hasResultSet = statement.execute(sql);
            while (!hasResultSet && statement.getUpdateCount() != -1) {
                hasResultSet = statement.getMoreResults();
            }
            if (!hasResultSet) {
                throw new SqlExecutionException("statement did not return a result set", null, null);
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return read(resultSet);
            }
        } finally {
            connection.rollback();
            connection.setAutoCommit(previousAutoCommit);
            connection.setReadOnly(previousReadOnly);
        }
    }

    private QueryResult read(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnLabel(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() == maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(toJsonValue(resultSet.getObject(i)));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, truncated);
    }

    /** JSON으로 나갈 값으로 바꾼다. 숫자·불리언·문자열은 그대로, 날짜는 java.time, 그 밖의 타입은 문자열. */
    private static Object toJsonValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return value.toString();
    }
}
