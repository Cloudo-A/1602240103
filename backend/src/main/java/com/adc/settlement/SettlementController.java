package com.adc.settlement;

import com.adc.auth.CurrentUser;
import com.adc.settlement.SettlementDtos.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class SettlementController {
    private final SettlementService service;

    SettlementController(SettlementService service) {
        this.service = service;
    }

    @PostMapping("/visits/{id}/settlement-preview")
    Preview preview(@PathVariable long id) {
        return service.preview(id);
    }

    @PostMapping("/visits/{id}/settlement")
    ResponseEntity<Slip> issue(@PathVariable long id, @RequestBody(required = false) Issue request,
                               @AuthenticationPrincipal CurrentUser user) {
        var result = service.issue(id, request == null ? null : request.checkToken(), user);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.settlement());
    }

    @GetMapping("/settlements")
    List<Slip> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                    @RequestParam(defaultValue = "") String keyword) {
        return service.list(from, to, keyword.strip());
    }

    @GetMapping("/settlements/{settlementNo}")
    Slip detail(@PathVariable String settlementNo) {
        return service.detail(settlementNo);
    }
}
