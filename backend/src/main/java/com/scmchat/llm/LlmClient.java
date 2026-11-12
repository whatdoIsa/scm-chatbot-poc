package com.scmchat.llm;

import java.util.List;

public interface LlmClient {

    /** 호출에 실패하면 {@link LlmUnavailableException}을 던진다. */
    LlmReply generate(List<LlmMessage> messages);

    String modelName();
}
