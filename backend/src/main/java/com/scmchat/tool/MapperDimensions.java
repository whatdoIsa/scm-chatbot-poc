package com.scmchat.tool;

import com.scmchat.executor.SqlExecutionException;
import com.scmchat.mapper.MetricToolMapper;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** 품목과 거점을 DB의 차원 테이블에서 확인한다. DB 오류는 조회 도구 실행과 같은 예외로 바꾼다. */
@Component
public class MapperDimensions implements Dimensions {

    private final MetricToolMapper mapper;

    public MapperDimensions(MetricToolMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean skuExists(String sku) {
        try {
            return mapper.skuExists(sku);
        } catch (DataAccessException e) {
            throw SqlExecutionException.from(e);
        }
    }

    @Override
    public List<String> locationNames() {
        try {
            return mapper.locationNames();
        } catch (DataAccessException e) {
            throw SqlExecutionException.from(e);
        }
    }
}
