package com.adc.visit;

import com.adc.auth.CurrentUser;
import com.adc.common.BusinessException;
import com.adc.visit.VisitDtos.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.*;

@RestController
@RequestMapping("/api/visits")
class VisitController {
    private final VisitQueries queries;
    private final VisitService visits;
    private final ObjectReader decimalTree;
    private final ObjectMapper json;

    VisitController(VisitQueries queries, VisitService visits, ObjectMapper json) {
        this.queries = queries;
        this.visits = visits;
        this.json = json;
        this.decimalTree = json.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @GetMapping
    List<Summary> list(@RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
                       @RequestParam(required=false) String status, @RequestParam(required=false) Long dentistId,
                       @AuthenticationPrincipal CurrentUser user) {
        return queries.list(from,to,status,dentistId,user);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        return queries.detail(id,user);
    }

    @PostMapping("/{id}/price-lines")
    Preview price(@PathVariable long id, @RequestBody(required=false) String body, @AuthenticationPrincipal CurrentUser user) {
        JsonNode root = tree(body);
        return visits.preview(id,user,root == null ? null : root.get("treatments"));
    }

    @PostMapping("/{id}/confirm")
    Detail confirm(@PathVariable long id, @RequestBody String body, @AuthenticationPrincipal CurrentUser user) {
        JsonNode root = tree(body);
        if (root == null || !root.isObject()) throw BusinessException.invalid("body","请提交诊疗记录");
        // Preserve the request's exact decimal values in the treatment tree.
        Confirm form;
        try {
            form = json.treeToValue(root, Confirm.class);
        } catch (JacksonException ex) {
            throw BusinessException.invalid("body","诊疗记录字段格式错误");
        }
        return visits.confirm(id,user,form);
    }

    @PostMapping("/{id}/end-without-treatment")
    Detail end(@PathVariable long id, @RequestBody End form, @AuthenticationPrincipal CurrentUser user) {
        return visits.end(id,user,form);
    }

    private JsonNode tree(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return decimalTree.readValue(body);
        } catch (JacksonException ex) {
            throw BusinessException.invalid("body","请求体必须是有效的JSON");
        }
    }
}
