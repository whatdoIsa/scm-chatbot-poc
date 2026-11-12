package com.scmchat.eval;

import com.scmchat.executor.QueryResult;
import com.scmchat.llm.Action;
import com.scmchat.service.ChatResult;
import com.scmchat.service.ChatStatus;
import org.springframework.stereotype.Component;

/**
 * 문항 하나를 채점한다. clarify·refuse 문항은 action만 보고, sql 문항은 실행 결과를 정답 SQL 결과와 비교한다.
 * 원인을 자동으로 붙이는 것은 형식 오류, action 불일치, SQL 실행 오류뿐이다. 값이 다른 오답은 미분류로 둔다.
 */
@Component
public class Grader {

    private final ResultComparator comparator;

    public Grader(ResultComparator comparator) {
        this.comparator = comparator;
    }

    public GradeResult grade(GoldenItem item, ChatResult actual, QueryResult expected) {
        if (actual.status() == ChatStatus.FORMAT_ERROR) {
            return new GradeResult(false, ErrorType.FORMAT_ERROR, actual.message());
        }
        if (actual.status() == ChatStatus.LLM_UNAVAILABLE) {
            return new GradeResult(false, null, "LLM unavailable: " + actual.message());
        }
        if (actual.action() != item.expectedAction()) {
            return new GradeResult(false, ErrorType.ACTION_MISMATCH,
                    "expected " + item.expectedAction().value() + " but was " + actual.action().value());
        }
        if (item.expectedAction() != Action.SQL) {
            return new GradeResult(true, null, null);
        }
        if (actual.status() != ChatStatus.OK) {
            return new GradeResult(false, ErrorType.SQL_EXECUTION_ERROR, actual.message());
        }
        ResultComparator.Comparison comparison = comparator.compare(expected, actual.result(), item.orderSensitive());
        return new GradeResult(comparison.match(), null, comparison.reason());
    }
}
