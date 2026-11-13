package com.scmchat.llm;

import com.scmchat.tool.RawToolArgs;

/** 라우터 출력. tool과 args의 값은 검증 전이며, route가 tool이 아니어도 모델이 낸 그대로 담긴다. */
public record ParsedRoute(Route route, String tool, RawToolArgs args, String message) {
}
