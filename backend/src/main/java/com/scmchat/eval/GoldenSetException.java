package com.scmchat.eval;

/** 골든셋 파일의 형식이 잘못됐거나 동결된 해시와 다를 때 던진다. */
public class GoldenSetException extends RuntimeException {

    public GoldenSetException(String message) {
        super(message);
    }

    public GoldenSetException(String message, Throwable cause) {
        super(message, cause);
    }
}
