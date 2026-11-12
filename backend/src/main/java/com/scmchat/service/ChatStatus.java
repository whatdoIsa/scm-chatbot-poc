package com.scmchat.service;

public enum ChatStatus {
    OK,
    SQL_REJECTED,
    SQL_EXECUTION_ERROR,
    FORMAT_ERROR,
    LLM_UNAVAILABLE
}
