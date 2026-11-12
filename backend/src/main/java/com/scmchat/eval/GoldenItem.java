package com.scmchat.eval;

import com.scmchat.llm.Action;

/** 골든셋 한 문항(API_SPEC 4.5). goldenSql은 expectedAction이 sql일 때만 있다. */
public record GoldenItem(
        String id,
        QuestionType type,
        String question,
        Action expectedAction,
        String goldenSql,
        boolean orderSensitive,
        String note) {
}
