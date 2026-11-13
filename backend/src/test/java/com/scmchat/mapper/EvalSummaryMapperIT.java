package com.scmchat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.entity.EvalResult;
import com.scmchat.entity.EvalRun;
import com.scmchat.repository.EvalResultRepository;
import com.scmchat.repository.EvalRunRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis 집계 SQL을 실제 app 스키마에서 확인한다. 테스트가 끝나면 롤백한다. */
@Tag("integration")
@SpringBootTest
@Transactional
class EvalSummaryMapperIT {

    private static final String MODEL = "mapper-it-model";

    @Autowired
    private EvalSummaryMapper mapper;

    @Autowired
    private EvalRunRepository evalRunRepository;

    @Autowired
    private EvalResultRepository evalResultRepository;

    @Autowired
    private EntityManager entityManager;

    private EvalRun finished;

    @BeforeEach
    void insertRuns() {
        finished = evalRunRepository.save(newRun());
        save(finished, "Q01", "SIMPLE_LOOKUP", true, null);
        save(finished, "Q02", "SIMPLE_LOOKUP", false, null);
        save(finished, "Q03", "SIMPLE_LOOKUP", false, "DATE_RANGE");
        save(finished, "Q25", "AMBIGUOUS", false, "ACTION_MISMATCH");
        save(finished, "Q26", "AMBIGUOUS", false, "ACTION_MISMATCH");
        finished.complete(OffsetDateTime.now(), 5, 1, new BigDecimal("0.2000"), null, new BigDecimal("1.0000"), 1200);
        evalRunRepository.save(finished);
        evalRunRepository.save(newRun());
        entityManager.flush();
    }

    @Test
    void listsOnlyFinishedRunsOfTheModel() {
        List<RunSummaryRow> rows = mapper.runSummaries(MODEL);

        assertThat(rows).hasSize(1);
        RunSummaryRow row = rows.get(0);
        assertThat(row.runId()).isEqualTo(finished.getId());
        assertThat(row.promptVersion()).isEqualTo("v0");
        assertThat(row.total()).isEqualTo(5);
        assertThat(row.correct()).isEqualTo(1);
        assertThat(row.accuracy()).isEqualByComparingTo("0.2");
        assertThat(row.sqlExecSuccessRate()).isNull();
        assertThat(row.avgElapsedMs()).isEqualTo(1200);
    }

    @Test
    void includesTheRunWhenModelIsNotGiven() {
        assertThat(mapper.runSummaries(null)).extracting(RunSummaryRow::runId).contains(finished.getId());
    }

    @Test
    void summarizesAccuracyByType() {
        assertThat(mapper.typeSummaries(finished.getId())).containsExactly(
                new TypeSummaryRow("SIMPLE_LOOKUP", 3, 1, new BigDecimal("0.3333")),
                new TypeSummaryRow("AMBIGUOUS", 2, 0, new BigDecimal("0.0000")));
    }

    @Test
    void countsWrongAnswersByErrorTypeWithUnclassified() {
        assertThat(mapper.errorTypeCounts(finished.getId())).containsExactly(
                new ErrorTypeCountRow("ACTION_MISMATCH", 2),
                new ErrorTypeCountRow("DATE_RANGE", 1),
                new ErrorTypeCountRow("UNCLASSIFIED", 1));
    }

    @Test
    void hasNoRouteSummaryForARunWithoutRoutes() {
        assertThat(mapper.routeSummaries(finished.getId())).isEmpty();
        assertThat(mapper.runSummaries(MODEL).get(0).answerVerifiedRate()).isNull();
    }

    @Test
    void summarizesCorrectAnswersByRouteAndReadsTheVerifiedRate() {
        EvalRun routedRun = evalRunRepository.save(newRun());
        saveRouted(routedRun, "Q01", "sql", true);
        saveRouted(routedRun, "Q02", "tool", true);
        saveRouted(routedRun, "Q03", "tool", false);
        saveRouted(routedRun, "Q04", "refuse", true);
        saveRouted(routedRun, "Q05", null, false);
        routedRun.complete(OffsetDateTime.now(), 5, 3, new BigDecimal("0.6000"), null, new BigDecimal("1.0000"), 900);
        routedRun.recordAnswerVerifiedRate(new BigDecimal("0.7500"));
        evalRunRepository.save(routedRun);
        entityManager.flush();

        assertThat(mapper.routeSummaries(routedRun.getId())).containsExactly(
                new RouteSummaryRow("tool", 2, 1),
                new RouteSummaryRow("sql", 1, 1),
                new RouteSummaryRow("refuse", 1, 1),
                new RouteSummaryRow("none", 1, 0));
        assertThat(mapper.runSummaries(MODEL)).filteredOn(row -> row.runId() == routedRun.getId())
                .singleElement()
                .satisfies(row -> assertThat(row.answerVerifiedRate()).isEqualByComparingTo("0.75"));
    }

    private void saveRouted(EvalRun run, String questionId, String route, boolean correct) {
        EvalResult result = new EvalResult(run, questionId, "METRIC", "sql", route, null, correct, null, "OK",
                1, 100, 10, 900, null);
        if (route != null) {
            result.recordRoute(route, null, null);
        }
        evalResultRepository.save(result);
    }

    private static EvalRun newRun() {
        return new EvalRun("v0", MODEL, new BigDecimal("0.00"), 42, 1, "b".repeat(64), OffsetDateTime.now());
    }

    private void save(EvalRun run, String questionId, String qtype, boolean correct, String errorType) {
        evalResultRepository.save(new EvalResult(run, questionId, qtype, "sql", "sql", "SELECT 1", correct,
                errorType, "OK", 1, 100, 10, 1200, null));
    }
}
