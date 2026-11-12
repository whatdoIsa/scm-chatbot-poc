package com.scmchat.prompt;

import com.scmchat.config.ChatProperties;
import com.scmchat.llm.LlmMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** prompts/v{n}.txt를 system 메시지로, 질문 원문을 user 메시지로 조립한다(API_SPEC 4.1). */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[A-Z_]+}}");

    private final Path promptsDir;

    public PromptBuilder(ChatProperties chatProperties) {
        this.promptsDir = Path.of(chatProperties.promptsDir());
        // 상대 경로는 작업 디렉터리에 따라 달라지므로 첫 요청이 아니라 기동할 때 확인한다.
        if (!Files.isDirectory(promptsDir)) {
            throw new IllegalStateException(
                    "chat.prompts-dir is not a directory: " + promptsDir.toAbsolutePath().normalize());
        }
        PromptVersion configured = PromptVersion.fromValue(chatProperties.promptVersion());
        if (!supports(configured)) {
            log.warn("chat.prompt-version={} is not implemented yet; /api/chat will answer 501 until it is",
                    configured.value());
        }
    }

    public boolean supports(PromptVersion version) {
        return version == PromptVersion.V0;
    }

    public List<LlmMessage> build(PromptVersion version, String question) {
        if (!supports(version)) {
            throw new UnsupportedOperationException("prompt version " + version.value() + " is not implemented yet");
        }
        String system = requireNoPlaceholder(readTemplate(version), version);
        return List.of(LlmMessage.system(system), LlmMessage.user(question));
    }

    private String readTemplate(PromptVersion version) {
        Path file = promptsDir.resolve(version.value() + ".txt");
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read prompt template " + file.toAbsolutePath(), e);
        }
    }

    private static String requireNoPlaceholder(String rendered, PromptVersion version) {
        Matcher matcher = PLACEHOLDER.matcher(rendered);
        if (matcher.find()) {
            throw new IllegalStateException(
                    "unresolved placeholder " + matcher.group() + " in prompt " + version.value());
        }
        return rendered;
    }
}
