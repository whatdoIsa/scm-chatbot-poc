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
import org.springframework.stereotype.Component;

/** prompts/v{n}.txt를 system 메시지로, 질문 원문을 user 메시지로 조립한다(API_SPEC 4.1). */
@Component
public class PromptBuilder {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[A-Z_]+}}");

    private final Path promptsDir;

    public PromptBuilder(ChatProperties chatProperties) {
        this.promptsDir = Path.of(chatProperties.promptsDir());
    }

    public List<LlmMessage> build(PromptVersion version, String question) {
        if (version != PromptVersion.V0) {
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
