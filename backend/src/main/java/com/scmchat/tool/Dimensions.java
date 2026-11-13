package com.scmchat.tool;

import java.util.List;

/** 도구 인자가 가리키는 품목과 거점이 실제로 있는지 확인하는 데 쓴다. */
public interface Dimensions {

    boolean skuExists(String sku);

    List<String> locationNames();
}
