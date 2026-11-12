package com.scmchat.repository;

import com.scmchat.entity.EvalResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalResultRepository extends JpaRepository<EvalResult, Long> {

    List<EvalResult> findByRun_IdOrderByQuestionId(Long runId);
}
