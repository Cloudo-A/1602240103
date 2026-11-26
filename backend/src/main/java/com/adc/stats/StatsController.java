package com.adc.stats;

import com.adc.appointment.AppointmentQueries;
import com.adc.appointment.AppointmentView;
import com.adc.auth.CurrentUser;
import com.adc.common.ApiError.FieldIssue;
import com.adc.common.BusinessException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stats")
class StatsController {
    private final StatsService stats;
    private final AppointmentQueries appointments;

    StatsController(StatsService stats, AppointmentQueries appointments) {
        this.stats = stats;
        this.appointments = appointments;
    }

    @GetMapping("/cancellations")
    StatsService.CancellationReport cancellations(@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to, @RequestParam(required=false) List<Long> clinicIds) {
        validateDates(from, to);
        return stats.cancellations(from, to, clinicIds);
    }

    @GetMapping("/cancellations/details")
    List<AppointmentView> details(@RequestParam(required=false) Long clinicId, @RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to, @AuthenticationPrincipal CurrentUser user) {
        validateDates(from, to);
        if (clinicId == null) throw BusinessException.invalid("clinicId", "请选择诊所");
        return appointments.list(from, to, clinicId, null, null, null, user);
    }

    @GetMapping("/demand-qualification")
    StatsService.DemandReport demand(@RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
                                     @RequestParam(required=false) List<Long> clinicIds) {
        validateDates(from, to);
        return stats.demand(from, to, clinicIds);
    }

    private void validateDates(LocalDate from, LocalDate to) {
        var issues = new ArrayList<FieldIssue>();
        if (from == null) issues.add(new FieldIssue("from", "请选择开始日期"));
        if (to == null) issues.add(new FieldIssue("to", "请选择结束日期"));
        if (from != null && to != null && from.isAfter(to)) issues.add(new FieldIssue("from", "开始日期不能晚于结束日期"));
        if (!issues.isEmpty()) throw BusinessException.invalid(issues);
    }
}
