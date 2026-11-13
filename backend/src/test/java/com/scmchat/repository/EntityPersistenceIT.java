package com.scmchat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.entity.ChatLog;
import com.scmchat.entity.EvalResult;
import com.scmchat.entity.EvalRun;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** 엔티티가 app 스키마의 실제 컬럼 타입으로 저장·조회되는지 확인한다. 테스트가 끝나면 롤백한다. */
@Tag("integration")
@SpringBootTest
@Transactional
class EntityPersistenceIT {

    @Autowired
    private ChatLogRepository chatLogRepository;

    @Autowired
    private EvalRunRepository evalRunRepository;

    @Autowired
    private EvalResultRepository evalResultRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndReadsChatLog() {
        ChatLog saved = chatLogRepository.save(new ChatLog(
                "SKU-007의 현재 재고는?", "v0", "qwen2.5-coder:7b", "sql", "OK",
                "SELECT 1", 1, 1, 120, 30, 850, null));
        flushAndClear();

        ChatLog found = chatLogRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getQuestion()).isEqualTo("SKU-007의 현재 재고는?");
        assertThat(found.getAttempts()).isEqualTo(1);
        assertThat(found.getRowCount()).isEqualTo(1);
        assertThat(found.getErrorMessage()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void savesEvalRunWithResult() {
        String sha256 = "a".repeat(64);
        EvalRun run = evalRunRepository.save(new EvalRun(
                "v0", "qwen2.5-coder:7b", new BigDecimal("0.00"), 42, 1, sha256, OffsetDateTime.now()));
        EvalResult result = evalResultRepository.save(new EvalResult(
                run, "Q01", "LOOKUP", "sql", "sql", "SELECT 1", true, null, "OK", 1, 100, 20, 700, null));
        flushAndClear();

        EvalResult found = evalResultRepository.findById(result.getId()).orElseThrow();

        assertThat(found.isCorrect()).isTrue();
        assertThat(found.getErrorType()).isNull();
        assertThat(found.getRun().getId()).isEqualTo(run.getId());
        assertThat(found.getRun().getGoldenSha256()).isEqualTo(sha256);
        assertThat(found.getRun().getTemperature()).isEqualByComparingTo("0");
        assertThat(found.getRun().getAccuracy()).isNull();
    }

    @Test
    void savesRouteAndToolCallOnChatLogAndEvalResult() {
        ChatLog chatLog = new ChatLog("SKU-012의 재고보유일수는?", "v3", "qwen2.5-coder:7b", "tool", "OK",
                null, 1, 1, 900, 40, 3100, null);
        chatLog.recordRoute("tool", "days_of_supply", "{\"sku\":\"SKU-012\",\"output\":\"value\"}");
        chatLog = chatLogRepository.save(chatLog);
        EvalRun run = evalRunRepository.save(new EvalRun(
                "v3", "qwen2.5-coder:7b", new BigDecimal("0.00"), 42, 1, "b".repeat(64), OffsetDateTime.now()));
        EvalResult result = new EvalResult(
                run, "Q15", "METRIC", "sql", "tool", null, true, null, "OK", 1, 900, 40, 3100, null);
        result.recordRoute("tool", "days_of_supply", "{\"sku\":\"SKU-012\",\"output\":\"value\"}");
        result = evalResultRepository.save(result);
        flushAndClear();

        ChatLog foundLog = chatLogRepository.findById(chatLog.getId()).orElseThrow();
        EvalResult foundResult = evalResultRepository.findById(result.getId()).orElseThrow();

        assertThat(foundLog.getRoute()).isEqualTo("tool");
        assertThat(foundLog.getToolName()).isEqualTo("days_of_supply");
        assertThat(foundLog.getToolArgs()).contains("SKU-012");
        assertThat(foundLog.getGeneratedSql()).isNull();
        assertThat(foundResult.getRoute()).isEqualTo("tool");
        assertThat(foundResult.getToolName()).isEqualTo("days_of_supply");
        assertThat(foundResult.getToolArgs()).contains("SKU-012");
    }

    @Test
    void savesAnswerTextStatusAndVerifiedRate() {
        ChatLog chatLog = new ChatLog("SKU-007의 현재 재고는?", "v3", "qwen2.5-coder:7b", "sql", "OK",
                "SELECT 1", 1, 3, 4000, 90, 9000, null);
        chatLog.recordAnswer("SKU-007의 현재 재고는 300개입니다.", "REJECTED");
        chatLog = chatLogRepository.save(chatLog);
        EvalRun run = new EvalRun(
                "v3", "qwen2.5-coder:7b", new BigDecimal("0.00"), 42, 1, "c".repeat(64), OffsetDateTime.now());
        run.recordAnswerVerifiedRate(new BigDecimal("0.8750"));
        run = evalRunRepository.save(run);
        EvalResult result = new EvalResult(
                run, "Q01", "SIMPLE_LOOKUP", "sql", "sql", "SELECT 1", true, null, "OK", 3, 4000, 90, 9000, null);
        result.recordAnswer("SKU-007의 현재 재고는 284개입니다.", "VERIFIED");
        result = evalResultRepository.save(result);
        flushAndClear();

        ChatLog foundLog = chatLogRepository.findById(chatLog.getId()).orElseThrow();
        EvalResult foundResult = evalResultRepository.findById(result.getId()).orElseThrow();

        assertThat(foundLog.getAnswerStatus()).isEqualTo("REJECTED");
        assertThat(foundLog.getAnswerText()).contains("300개");
        assertThat(foundResult.getAnswerStatus()).isEqualTo("VERIFIED");
        assertThat(foundResult.getAnswerText()).contains("284개");
        assertThat(foundResult.getRun().getAnswerVerifiedRate()).isEqualByComparingTo("0.875");
    }

    @Test
    void leavesRouteColumnsNullWhenNotRouted() {
        ChatLog saved = chatLogRepository.save(new ChatLog(
                "SKU-007의 현재 재고는?", "v1", "qwen2.5-coder:7b", "sql", "OK", "SELECT 1", 1, 1, 120, 30, 850, null));
        flushAndClear();

        ChatLog found = chatLogRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getRoute()).isNull();
        assertThat(found.getToolName()).isNull();
        assertThat(found.getToolArgs()).isNull();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
