package com.scmchat.answer;

import java.util.List;

/** unverified는 허용 집합에 없던 숫자 토큰(문장에 적힌 그대로)이다. */
public record Verification(boolean ok, List<String> unverified) {
}
