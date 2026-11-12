package com.scmchat.eval;

public record GradeResult(boolean correct, ErrorType errorType, String detail) {
}
