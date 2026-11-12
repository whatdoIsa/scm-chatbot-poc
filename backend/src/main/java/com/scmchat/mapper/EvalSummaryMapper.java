package com.scmchat.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 평가 결과 집계. SQL은 resources/mapper/EvalSummaryMapper.xml에 있다. */
@Mapper
public interface EvalSummaryMapper {

    /** 끝난 실행을 최신 순으로 돌려준다. model이 null이면 전체. */
    List<RunSummaryRow> runSummaries(@Param("model") String model);

    List<TypeSummaryRow> typeSummaries(@Param("runId") long runId);

    /** 오답만 센다. error_type이 비어 있으면 UNCLASSIFIED로 묶는다. */
    List<ErrorTypeCountRow> errorTypeCounts(@Param("runId") long runId);
}
