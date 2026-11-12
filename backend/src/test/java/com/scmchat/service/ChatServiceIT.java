package com.scmchat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.entity.ChatLog;
import com.scmchat.llm.Action;
import com.scmchat.llm.FakeLlmClient;
import com.scmchat.prompt.PromptVersion;
import com.scmchat.repository.ChatLogRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Fake 응답을 실제 DB에 실행해 본다. chat_log 저장은 테스트 트랜잭션에 묶여 끝나면 롤백된다. */
@Tag("integration")
@SpringBootTest(properties = "chat.llm.provider=fake")
@Transactional
class ChatServiceIT {

    @Autowired
    private ChatService chatService;

    @Autowired
    private FakeLlmClient fakeLlm;

    @Autowired
    private ChatLogRepository chatLogRepository;

    @Test
    void executesGeneratedSqlAgainstScmSchemaAndLogsIt() {
        long before = chatLogRepository.count();
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"SELECT count(*) AS products FROM product;\",\"message\":\"\"}");

        ChatResult result = chatService.answer("품목은 몇 개야?", PromptVersion.V0);

        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.action()).isEqualTo(Action.SQL);
        assertThat(result.result().columns()).containsExactly("products");
        assertThat(result.result().rows().get(0).get(0)).isEqualTo(50L);

        assertThat(chatLogRepository.count()).isEqualTo(before + 1);
        ChatLog saved = chatLogRepository.findById(result.chatLogId()).orElseThrow();
        assertThat(saved.getQuestion()).isEqualTo("품목은 몇 개야?");
        assertThat(saved.getAction()).isEqualTo("sql");
        assertThat(saved.getStatus()).isEqualTo("OK");
        assertThat(saved.getGeneratedSql()).isEqualTo("SELECT count(*) AS products FROM product");
        assertThat(saved.getRowCount()).isEqualTo(1);
        assertThat(saved.getModel()).isEqualTo("fake");
    }

    @Test
    void logsExecutionErrorWithPostgresMessage() {
        long before = chatLogRepository.count();
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"SELECT stock FROM inventory\",\"message\":\"\"}");

        ChatResult result = chatService.answer("재고 알려줘", PromptVersion.V0);

        assertThat(result.status()).isEqualTo(ChatStatus.SQL_EXECUTION_ERROR);
        assertThat(result.message()).contains("relation \"inventory\" does not exist");
        assertThat(chatLogRepository.count()).isEqualTo(before + 1);
        ChatLog saved = chatLogRepository.findById(result.chatLogId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("SQL_EXECUTION_ERROR");
        assertThat(saved.getErrorMessage()).contains("inventory");
    }

    @Test
    void doesNotApplyDataChangesFromGeneratedSql() {
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"UPDATE inventory_daily SET on_hand = 0\",\"message\":\"\"}");

        ChatResult result = chatService.answer("SKU-007 재고를 0으로 바꿔줘", PromptVersion.V0);

        assertThat(result.status()).isEqualTo(ChatStatus.SQL_EXECUTION_ERROR);
        assertThat(result.message()).contains("read-only transaction");
    }

    @Test
    void answersWithDefaultFakeClarify() {
        ChatResult result = chatService.answer("재고 어때?", PromptVersion.V0);

        assertThat(result.action()).isEqualTo(Action.CLARIFY);
        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.message()).isEqualTo("fake");
    }
}
