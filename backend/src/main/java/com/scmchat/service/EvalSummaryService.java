package com.scmchat.service;

import com.scmchat.dto.EvalSummaryResponse;
import com.scmchat.mapper.EvalSummaryMapper;
import com.scmchat.mapper.RunSummaryRow;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 평가 실행별 요약에 유형별 정확도와 오답 원인별 건수를 붙인다. */
@Service
public class EvalSummaryService {

    private final EvalSummaryMapper mapper;

    public EvalSummaryService(EvalSummaryMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public EvalSummaryResponse summarize(String model) {
        List<EvalSummaryResponse.Run> runs = mapper.runSummaries(model).stream().map(this::toRun).toList();
        return new EvalSummaryResponse(runs);
    }

    private EvalSummaryResponse.Run toRun(RunSummaryRow row) {
        List<EvalSummaryResponse.TypeAccuracy> byType = mapper.typeSummaries(row.runId()).stream()
                .map(type -> new EvalSummaryResponse.TypeAccuracy(
                        type.type(), type.total(), type.correct(), type.accuracy()))
                .toList();
        List<EvalSummaryResponse.ErrorTypeCount> errorTypes = mapper.errorTypeCounts(row.runId()).stream()
                .map(error -> new EvalSummaryResponse.ErrorTypeCount(error.errorType(), error.count()))
                .toList();
        return new EvalSummaryResponse.Run(row.runId(), row.promptVersion(), row.model(), row.temperature(),
                row.seed(), row.repeatNo(), row.total(), row.correct(), row.accuracy(), row.sqlExecSuccessRate(),
                row.formatOkRate(), row.avgElapsedMs(), byType, errorTypes);
    }
}
