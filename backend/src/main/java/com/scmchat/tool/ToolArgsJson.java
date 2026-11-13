package com.scmchat.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** 도구 인자를 chat_log, eval_result의 tool_args 컬럼에 넣을 JSON 문자열로 바꾼다. */
public final class ToolArgsJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolArgsJson() {
    }

    public static String write(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize tool arguments " + args, e);
        }
    }
}
