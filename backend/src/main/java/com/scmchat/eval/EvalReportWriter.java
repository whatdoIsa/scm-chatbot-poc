package com.scmchat.eval;

import com.scmchat.config.EvalProperties;
import com.scmchat.entity.EvalResult;
import com.scmchat.entity.EvalRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 평가 1회의 결과를 콘솔과 eval/reports/run-{id}-{version}.md로 낸다.
 * 경로가 기록된 실행(v3)에만 경로별 정확도와 답변 문장 후검증 절이 붙는다. v0~v2 리포트의 형식은 그대로다.
 */
@Component
public class EvalReportWriter {

    static final String UNCLASSIFIED = "UNCLASSIFIED";
    private static final List<String> ROUTE_ORDER = List.of("tool", "sql", "clarify", "refuse", "none");

    private final Path reportDir;

    public EvalReportWriter(EvalProperties evalProperties) {
        this.reportDir = Path.of(evalProperties.reportDir());
    }

    public Path write(EvalRun run, List<EvalResult> results, List<GoldenItem> items) {
        String report = render(run, results, items);
        Path file = reportDir.resolve("run-" + run.getId() + "-" + run.getPromptVersion() + ".md");
        try {
            Files.createDirectories(reportDir);
            Files.writeString(file, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write eval report " + file.toAbsolutePath().normalize(), e);
        }
        System.out.println(report);
        System.out.println("report: " + file.toAbsolutePath().normalize());
        return file;
    }

    String render(EvalRun run, List<EvalResult> results, List<GoldenItem> items) {
        Map<String, GoldenItem> itemsById =
                items.stream().collect(Collectors.toMap(GoldenItem::id, Function.identity()));
        StringBuilder out = new StringBuilder();
        out.append("# 평가 실행 ").append(run.getId()).append(" (").append(run.getPromptVersion()).append(")\n\n");
        out.append("| 항목 | 값 |\n| --- | --- |\n");
        row(out, "모델", run.getModel());
        row(out, "프롬프트 버전", run.getPromptVersion());
        row(out, "temperature / seed", run.getTemperature().toPlainString() + " / " + run.getSeed());
        row(out, "반복 번호", run.getRepeatNo());
        row(out, "골든셋 sha256", run.getGoldenSha256());
        row(out, "정확도", run.getCorrect() + " / " + run.getTotal() + " = " + run.getAccuracy());
        row(out, "SQL 실행 성공률", run.getSqlExecSuccessRate() == null ? "해당 없음" : run.getSqlExecSuccessRate());
        row(out, "형식 준수율", run.getFormatOkRate());
        row(out, "평균 응답 시간(ms)", run.getAvgElapsedMs());
        row(out, "평균 프롬프트 토큰", average(results.stream().mapToInt(EvalResult::getPromptTokens).sum(), results.size()));
        row(out, "평균 출력 토큰", average(results.stream().mapToInt(EvalResult::getCompletionTokens).sum(), results.size()));
        boolean routed = results.stream().anyMatch(result -> result.getRoute() != null);
        if (routed) {
            row(out, "평균 LLM 호출 수", average(results.stream().mapToInt(EvalResult::getAttempts).sum(), results.size()));
            row(out, "숫자 검증 통과율",
                    run.getAnswerVerifiedRate() == null ? "해당 없음" : run.getAnswerVerifiedRate());
        }

        out.append("\n## 유형별 정확도\n\n| 유형 | 문항 | 정답 | 정확도 |\n| --- | --- | --- | --- |\n");
        Map<String, List<EvalResult>> byType = results.stream()
                .collect(Collectors.groupingBy(EvalResult::getQtype, LinkedHashMap::new, Collectors.toList()));
        byType.forEach((type, typeResults) -> {
            long correct = typeResults.stream().filter(EvalResult::isCorrect).count();
            out.append("| ").append(type).append(" | ").append(typeResults.size()).append(" | ").append(correct)
                    .append(" | ").append(average(correct, typeResults.size())).append(" |\n");
        });

        if (routed) {
            appendRoutes(out, results);
            appendAnswers(out, results, itemsById);
        }

        List<EvalResult> wrong = results.stream().filter(result -> !result.isCorrect()).toList();
        out.append("\n## 오답 원인별 건수\n\n");
        if (wrong.isEmpty()) {
            out.append("오답 없음.\n");
        } else {
            out.append("| 원인 | 건수 |\n| --- | --- |\n");
            wrong.stream()
                    .collect(Collectors.groupingBy(EvalReportWriter::errorTypeOf, LinkedHashMap::new,
                            Collectors.counting()))
                    .forEach((errorType, count) ->
                            out.append("| ").append(errorType).append(" | ").append(count).append(" |\n"));
        }

        out.append("\n## 오답 목록\n\n");
        if (wrong.isEmpty()) {
            out.append("오답 없음.\n");
        } else {
            out.append("| id | 유형 | 질문 | 기대 | 실제 | status | error_type | 생성 SQL | 상세 |\n");
            out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
            for (EvalResult result : wrong) {
                GoldenItem item = itemsById.get(result.getQuestionId());
                out.append("| ").append(result.getQuestionId())
                        .append(" | ").append(result.getQtype())
                        .append(" | ").append(cell(item == null ? null : item.question()))
                        .append(" | ").append(result.getExpectedAction())
                        .append(" | ").append(cell(result.getActualAction()))
                        .append(" | ").append(result.getStatus())
                        .append(" | ").append(errorTypeOf(result))
                        .append(" | ").append(cell(queryOf(result)))
                        .append(" | ").append(cell(result.getDetail()))
                        .append(" |\n");
            }
        }
        return out.toString();
    }

    /** v3: 라우터가 고른 경로별 문항 수와 정답 수. 라우터 호출이 실패한 문항은 none이다. */
    private static void appendRoutes(StringBuilder out, List<EvalResult> results) {
        out.append("\n## 경로별 정확도\n\n| 경로 | 문항 | 정답 | 정확도 |\n| --- | --- | --- | --- |\n");
        for (String route : ROUTE_ORDER) {
            List<EvalResult> routeResults = results.stream().filter(result -> route.equals(routeOf(result))).toList();
            if (routeResults.isEmpty()) {
                continue;
            }
            long correct = routeResults.stream().filter(EvalResult::isCorrect).count();
            out.append("| ").append(route).append(" | ").append(routeResults.size()).append(" | ").append(correct)
                    .append(" | ").append(average(correct, routeResults.size())).append(" |\n");
        }
    }

    /** v3: 답변 문장의 후검증 결과와, 숫자가 맞지 않아 응답에서 뺀 문장. */
    private static void appendAnswers(StringBuilder out, List<EvalResult> results, Map<String, GoldenItem> itemsById) {
        out.append("\n## 답변 문장 후검증\n\n| 결과 | 건수 |\n| --- | --- |\n");
        for (String status : List.of("VERIFIED", "REJECTED", "SKIPPED")) {
            long count = results.stream().filter(result -> status.equals(result.getAnswerStatus())).count();
            out.append("| ").append(status).append(" | ").append(count).append(" |\n");
        }
        long none = results.stream().filter(result -> result.getAnswerStatus() == null).count();
        out.append("| 조회 결과 없음 | ").append(none).append(" |\n");

        List<EvalResult> rejected =
                results.stream().filter(result -> "REJECTED".equals(result.getAnswerStatus())).toList();
        out.append("\n### 거부된 문장\n\n");
        if (rejected.isEmpty()) {
            out.append("없음.\n");
            return;
        }
        out.append("| id | 질문 | 문장 | 조회 정답 |\n| --- | --- | --- | --- |\n");
        for (EvalResult result : rejected) {
            GoldenItem item = itemsById.get(result.getQuestionId());
            out.append("| ").append(result.getQuestionId())
                    .append(" | ").append(cell(item == null ? null : item.question()))
                    .append(" | ").append(cell(result.getAnswerText()))
                    .append(" | ").append(result.isCorrect() ? "정답" : "오답")
                    .append(" |\n");
        }
    }

    private static String routeOf(EvalResult result) {
        return result.getRoute() == null ? "none" : result.getRoute();
    }

    /** 도구로 답한 문항은 SQL이 없으므로 도구 호출을 대신 보여준다. */
    private static String queryOf(EvalResult result) {
        if (result.getGeneratedSql() == null && result.getToolName() != null) {
            return "tool: " + result.getToolName() + " " + result.getToolArgs();
        }
        return result.getGeneratedSql();
    }

    private static String errorTypeOf(EvalResult result) {
        return result.getErrorType() == null ? UNCLASSIFIED : result.getErrorType();
    }

    private static void row(StringBuilder out, String name, Object value) {
        out.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    private static BigDecimal average(long sum, long count) {
        return count == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    /** 표가 깨지지 않게 줄바꿈과 세로 막대를 바꾼다. */
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").replace("|", "\\|");
    }
}
