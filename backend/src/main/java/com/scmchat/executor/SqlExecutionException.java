package com.scmchat.executor;

import java.sql.SQLException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;

/** SQL 실행이 실패했을 때 던진다. 메시지는 PostgreSQL 오류 문구이며 v2 재시도 프롬프트에 그대로 들어간다. */
public class SqlExecutionException extends RuntimeException {

    private final String sqlState;

    public SqlExecutionException(String message, String sqlState, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    /** Spring이 감싼 DB 예외에서 PostgreSQL 오류 문구와 SQLSTATE를 꺼낸다. 조회 도구 쪽에서 쓴다. */
    public static SqlExecutionException from(DataAccessException e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        String sqlState = root instanceof SQLException sqlException ? sqlException.getSQLState() : null;
        return new SqlExecutionException(root.getMessage(), sqlState, e);
    }

    public String getSqlState() {
        return sqlState;
    }
}
