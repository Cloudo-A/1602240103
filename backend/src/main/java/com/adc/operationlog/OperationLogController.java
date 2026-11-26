package com.adc.operationlog;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operation-logs")
class OperationLogController {
    private final OperationLogQueryService service;

    OperationLogController(OperationLogQueryService service) {
        this.service = service;
    }

    @GetMapping
    OperationLogQueryService.Page list(@RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(defaultValue = "") String action,
                                      @RequestParam(defaultValue = "") String targetType,
                                      @RequestParam(defaultValue = "") String keyword,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.list(from, to, userId, action, targetType, keyword, page, size);
    }
}
