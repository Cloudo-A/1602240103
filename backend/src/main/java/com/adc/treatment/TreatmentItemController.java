package com.adc.treatment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/treatment-items")
class TreatmentItemController {
    record PriceInput(
        @NotNull(message = "请填写适用起始日期") LocalDate effectiveFrom,
        @NotNull(message = "请填写单位价格") @PositiveOrZero(message = "单位价格不能为负数")
        @Digits(integer = 16, fraction = 2, message = "单位价格最多十六位整数、两位小数") BigDecimal unitPrice) {}

    record Create(
        @NotBlank(message = "请填写项目名称") @Size(max = 100, message = "项目名称最多100字") String name,
        @NotBlank(message = "请填写标准单位") @Size(max = 30, message = "标准单位最多30字") String standardUnit,
        @NotNull(message = "请填写数量精度") @Min(value = 0, message = "数量精度应为0至6")
        @Max(value = 6, message = "数量精度应为0至6") Short quantityScale,
        @Valid @NotNull(message = "请填写首次价格") PriceInput firstPrice) {}

    record Edit(
        @NotBlank(message = "请填写项目名称") @Size(max = 100, message = "项目名称最多100字") String name,
        @NotBlank(message = "请填写标准单位") @Size(max = 30, message = "标准单位最多30字") String standardUnit,
        @NotNull(message = "请填写数量精度") @Min(value = 0, message = "数量精度应为0至6")
        @Max(value = 6, message = "数量精度应为0至6") Short quantityScale,
        @NotNull(message = "请选择使用状态") Boolean active,
        @NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号不能为负数") Integer version) {}

    record Price(long id, LocalDate effectiveFrom, BigDecimal unitPrice) {}
    record Item(long id, String name, String standardUnit, int quantityScale, boolean active,
                int version, BigDecimal currentPrice, List<Price> prices) {}

    private final TreatmentItemService service;

    TreatmentItemController(TreatmentItemService service) {
        this.service = service;
    }

    @GetMapping
    List<Item> list() { return service.list(); }

    @GetMapping("/{id}")
    Item get(@PathVariable long id) { return service.get(id); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Item create(@Valid @RequestBody Create input) { return service.create(input); }

    @PutMapping("/{id}")
    Item update(@PathVariable long id, @Valid @RequestBody Edit input) {
        return service.update(id, input);
    }

    @PostMapping("/{id}/prices")
    Item addPrice(@PathVariable long id, @Valid @RequestBody PriceInput input) {
        return service.addPrice(id, input);
    }
}
