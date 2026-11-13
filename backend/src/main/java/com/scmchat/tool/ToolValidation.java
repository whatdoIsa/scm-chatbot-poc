package com.scmchat.tool;

/** ok면 call이, 아니면 reason이 있다. */
public record ToolValidation(boolean ok, ToolCall call, String reason) {

    public static ToolValidation accepted(ToolCall call) {
        return new ToolValidation(true, call, null);
    }

    public static ToolValidation rejected(String reason) {
        return new ToolValidation(false, null, reason);
    }
}
