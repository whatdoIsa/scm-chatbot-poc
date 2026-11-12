package com.scmchat.eval;

import com.scmchat.config.ChatProperties;
import com.scmchat.config.EvalProperties;
import com.scmchat.entity.EvalResult;
import com.scmchat.entity.EvalRun;
import com.scmchat.executor.QueryResult;
import com.scmchat.executor.ReadOnlySqlExecutor;
import com.scmchat.llm.Action;
import com.scmchat.llm.LlmClient;
import com.scmchat.prompt.PromptVersion;
import com.scmchat.repository.EvalResultRepository;
import com.scmchat.repository.EvalRunRepository;
import com.scmchat.service.ChatResult;
import com.scmchat.service.ChatService;
import com.scmchat.service.ChatStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * eval 프로파일에서 골든셋 전체를 채점한다. 동결된 해시와 골든셋이 다르면 시작하지 않는다.
 * 문항은 HTTP를 거치지 않고 ChatService.answer로 직접 처리하며, 끝나면 애플리케이션을 종료한다.
 */
@Component
@Profile("eval")
public class EvalRunner implements ApplicationRunner {

    private final EvalProperties evalProperties;
    private final ChatProperties chatProperties;
    private final GoldenSetLoader goldenSetLoader;
    private final ChatService chatService;
    private final ReadOnlySqlExecutor executor;
    private final Grader grader;
    private final LlmClient llmClient;
    private final EvalRunRepository evalRunRepository;
    private final EvalResultRepository evalResultRepository;
    private final EvalReportWriter reportWriter;
    private final ApplicationContext applicationContext;

    public EvalRunner(EvalProperties evalProperties, ChatProperties chatProperties,
            GoldenSetLoader goldenSetLoader, ChatService chatService, ReadOnlySqlExecutor executor, Grader grader,
            LlmClient llmClient, EvalRunRepository evalRunRepository, EvalResultRepository evalResultRepository,
            EvalReportWriter reportWriter, ApplicationContext applicationContext) {
        this.evalProperties = evalProperties;
        this.chatProperties = chatProperties;
        this.goldenSetLoader = goldenSetLoader;
        this.chatService = chatService;
        this.executor = executor;
        this.grader = grader;
        this.llmClient = llmClient;
        this.evalRunRepository = evalRunRepository;
        this.evalResultRepository = evalResultRepository;
        this.reportWriter = reportWriter;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path goldenFile = Path.of(evalProperties.goldenPath());
        String goldenSha256 = GoldenSetHash.verify(goldenFile, Path.of(evalProperties.goldenHashPath()));
        List<GoldenItem> items = goldenSetLoader.load(goldenFile);

        for (String versionValue : evalProperties.versions()) {
            PromptVersion version = PromptVersion.fromValue(versionValue);
            for (int repeatNo = 1; repeatNo <= evalProperties.repeat(); repeatNo++) {
                runOnce(version, repeatNo, goldenSha256, items);
            }
        }
        SpringApplication.exit(applicationContext, () -> 0);
    }

    private void runOnce(PromptVersion version, int repeatNo, String goldenSha256, List<GoldenItem> items) {
        ChatProperties.Llm llm = chatProperties.llm();
        EvalRun run = evalRunRepository.save(new EvalRun(version.value(), llmClient.modelName(),
                BigDecimal.valueOf(llm.temperature()), llm.seed(), repeatNo, goldenSha256, OffsetDateTime.now()));

        List<EvalResult> results = new ArrayList<>();
        for (GoldenItem item : items) {
            ChatResult actual = chatService.answer(item.question(), version);
            QueryResult expected = item.expectedAction() == Action.SQL ? executor.execute(item.goldenSql()) : null;
            GradeResult grade = grader.grade(item, actual, expected);
            results.add(evalResultRepository.save(new EvalResult(
                    run,
                    item.id(),
                    item.type().name(),
                    item.expectedAction().value(),
                    actual.action() == null ? null : actual.action().value(),
                    actual.sql(),
                    grade.correct(),
                    grade.errorType() == null ? null : grade.errorType().name(),
                    actual.status().name(),
                    actual.attempts(),
                    actual.promptTokens(),
                    actual.completionTokens(),
                    actual.elapsedMs(),
                    grade.detail())));
        }

        int total = results.size();
        int correct = (int) results.stream().filter(EvalResult::isCorrect).count();
        long sqlAnswers = results.stream().filter(result -> "sql".equals(result.getActualAction())).count();
        long sqlExecuted = results.stream()
                .filter(result -> "sql".equals(result.getActualAction()))
                .filter(result -> ChatStatus.OK.name().equals(result.getStatus()))
                .count();
        long formatOk = results.stream()
                .filter(result -> !ChatStatus.FORMAT_ERROR.name().equals(result.getStatus()))
                .count();
        int avgElapsedMs = (int) Math.round(results.stream().mapToInt(EvalResult::getElapsedMs).average().orElse(0));

        run.complete(OffsetDateTime.now(), total, correct, ratio(correct, total),
                sqlAnswers == 0 ? null : ratio(sqlExecuted, sqlAnswers), ratio(formatOk, total), avgElapsedMs);
        evalRunRepository.save(run);
        reportWriter.write(run, results, items);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
