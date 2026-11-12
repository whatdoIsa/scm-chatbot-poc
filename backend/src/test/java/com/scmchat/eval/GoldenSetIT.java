package com.scmchat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmchat.config.ChatProperties;
import com.scmchat.config.EvalProperties;
import com.scmchat.executor.QueryResult;
import com.scmchat.executor.ReadOnlySqlExecutor;
import com.scmchat.llm.Action;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 사람이 작성한 eval/golden_set.json이 PRD 5.4, API_SPEC 4.5의 조건을 지키는지 실제 DB로 확인한다. */
@Tag("integration")
@SpringBootTest
class GoldenSetIT {

    private static final int MAX_GOLDEN_ROWS = 200;

    @Autowired
    private GoldenSetLoader loader;

    @Autowired
    private ReadOnlySqlExecutor executor;

    @Autowired
    private EvalProperties evalProperties;

    @Autowired
    private ChatProperties chatProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private List<GoldenItem> items;

    @BeforeEach
    void loadGoldenSet() {
        items = loader.load(Path.of(evalProperties.goldenPath()));
    }

    @Test
    void hasThirtyItemsWithIdsQ01ToQ30() {
        List<String> expectedIds = IntStream.rangeClosed(1, 30).mapToObj(i -> String.format("Q%02d", i)).toList();

        assertThat(items).extracting(GoldenItem::id).containsExactlyInAnyOrderElementsOf(expectedIds);
    }

    @Test
    void hasExpectedCountPerType() {
        Map<QuestionType, Long> counts =
                items.stream().collect(Collectors.groupingBy(GoldenItem::type, Collectors.counting()));

        assertThat(counts).containsOnly(
                Map.entry(QuestionType.SIMPLE_LOOKUP, 6L),
                Map.entry(QuestionType.PERIOD_AGG, 8L),
                Map.entry(QuestionType.METRIC, 6L),
                Map.entry(QuestionType.RANKING, 4L),
                Map.entry(QuestionType.AMBIGUOUS, 2L),
                Map.entry(QuestionType.REFUSE, 4L));
    }

    @Test
    void onlySqlItemsHaveGoldenSql() {
        assertThat(items).allSatisfy(item -> {
            if (item.expectedAction() == Action.SQL) {
                assertThat(item.goldenSql()).as(item.id()).isNotBlank();
            } else {
                assertThat(item.goldenSql()).as(item.id()).isNull();
            }
        });
    }

    @Test
    void everyGoldenSqlRunsAsScmRoAndReturnsOneTo200Rows() {
        List<String> problems = new ArrayList<>();
        for (GoldenItem item : sqlItems()) {
            try {
                QueryResult result = executor.execute(item.goldenSql());
                int rows = result.rows().size();
                if (rows < 1 || rows > MAX_GOLDEN_ROWS || result.truncated()) {
                    problems.add(item.id() + ": returned " + rows + " rows");
                }
            } catch (RuntimeException e) {
                problems.add(item.id() + ": " + e.getMessage());
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void rankingItemsAreOrderSensitiveAndOrderSensitiveItemsHaveOrderBy() {
        assertThat(items).filteredOn(item -> item.type() == QuestionType.RANKING)
                .allSatisfy(item -> assertThat(item.orderSensitive()).as(item.id()).isTrue());
        assertThat(items).filteredOn(GoldenItem::orderSensitive)
                .allSatisfy(item -> assertThat(item.goldenSql()).as(item.id()).containsIgnoringCase("ORDER BY"));
    }

    @Test
    void fewShotQuestionsDoNotOverlapGoldenQuestions() throws IOException {
        Path fewShotFile = Path.of(chatProperties.promptsDir()).resolve("fewshot.json");
        Assumptions.assumeTrue(Files.exists(fewShotFile), "prompts/fewshot.json is added in T9");

        List<String> fewShotQuestions = new ArrayList<>();
        for (JsonNode example : objectMapper.readTree(Files.readAllBytes(fewShotFile))) {
            fewShotQuestions.add(normalize(example.get("question").asText()));
        }
        List<String> goldenQuestions = items.stream().map(GoldenItem::question).map(GoldenSetIT::normalize).toList();

        assertThat(fewShotQuestions).doesNotContainAnyElementsOf(goldenQuestions);
    }

    private List<GoldenItem> sqlItems() {
        return items.stream().filter(item -> item.expectedAction() == Action.SQL).toList();
    }

    private static String normalize(String question) {
        return question.replaceAll("\\s+", "");
    }
}
