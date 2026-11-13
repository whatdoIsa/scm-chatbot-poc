package com.scmchat.llm;

import java.util.List;

public interface LlmClient {

    /** format의 JSON 스키마로 출력을 강제한다. 호출에 실패하면 {@link LlmUnavailableException}을 던진다. */
    LlmReply generate(List<LlmMessage> messages, OutputFormat format);

    String modelName();
}
