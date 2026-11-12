package com.scmchat.executor;

/** SQL 실행이 실패했을 때 던진다. 메시지는 PostgreSQL 오류 문구이며 v2 재시도 프롬프트에 그대로 들어간다. */
public class SqlExecutionException extends RuntimeException {

    private final String sqlState;

    public SqlExecutionException(String message, String sqlState, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public String getSqlState() {
        return sqlState;
    }
}
