package com.scmchat.validator;

/** ok면 sql은 실행할 SQL(LIMIT 적용 후), 아니면 sql은 원문이고 reason에 거부 사유가 있다. */
public record ValidationResult(boolean ok, String sql, String reason) {

    public static ValidationResult accepted(String sql) {
        return new ValidationResult(true, sql, null);
    }

    public static ValidationResult rejected(String sql, String reason) {
        return new ValidationResult(false, sql, reason);
    }
}
