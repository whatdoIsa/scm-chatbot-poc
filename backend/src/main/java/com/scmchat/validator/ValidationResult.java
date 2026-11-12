package com.scmchat.validator;

/**
 * ok면 sql은 실행할 SQL(LIMIT 적용 후), 아니면 sql은 원문이고 reason에 거부 사유가 있다.
 * limitApplied는 검증기가 LIMIT을 새로 붙였거나 낮췄는지를 말한다. 결과가 잘렸는지 판단하는 데 쓴다.
 */
public record ValidationResult(boolean ok, String sql, String reason, boolean limitApplied) {

    public static ValidationResult accepted(String sql, boolean limitApplied) {
        return new ValidationResult(true, sql, null, limitApplied);
    }

    public static ValidationResult rejected(String sql, String reason) {
        return new ValidationResult(false, sql, reason, false);
    }
}
