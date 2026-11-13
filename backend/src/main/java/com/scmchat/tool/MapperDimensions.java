package com.scmchat.tool;

import com.scmchat.mapper.MetricToolMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** 품목과 거점을 DB의 차원 테이블에서 확인한다. */
@Component
public class MapperDimensions implements Dimensions {

    private final MetricToolMapper mapper;

    public MapperDimensions(MetricToolMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean skuExists(String sku) {
        return mapper.skuExists(sku);
    }

    @Override
    public List<String> locationNames() {
        return mapper.locationNames();
    }
}
