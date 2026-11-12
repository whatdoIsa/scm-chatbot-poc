package com.scmchat.controller;

import com.scmchat.dto.EvalSummaryResponse;
import com.scmchat.service.EvalSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalSummaryService evalSummaryService;

    public EvalController(EvalSummaryService evalSummaryService) {
        this.evalSummaryService = evalSummaryService;
    }

    @GetMapping("/summary")
    public EvalSummaryResponse summary(@RequestParam(required = false) String model) {
        return evalSummaryService.summarize(model);
    }
}
