package com.scmchat.tool;

import java.util.List;

/**
 * 도구 인자가 가리키는 품목과 거점이 실제로 있는지 확인하는 데 쓴다.
 * 조회에 실패하면 {@link com.scmchat.executor.SqlExecutionException}을 던진다.
 */
public interface Dimensions {

    boolean skuExists(String sku);

    List<String> locationNames();

    List<String> regions();

    List<String> categories();
}
