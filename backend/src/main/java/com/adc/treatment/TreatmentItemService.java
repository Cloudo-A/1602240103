package com.adc.treatment;

import com.adc.common.BusinessException;
import com.adc.common.OperationLogService;
import com.adc.common.VersionedWrites;
import com.adc.treatment.TreatmentItemController.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class TreatmentItemService {
    private final EntityManager entities;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final OperationLogService logs;

    TreatmentItemService(EntityManager entities, JdbcClient jdbc, Clock clock, OperationLogService logs) {
        this.entities = entities;
        this.jdbc = jdbc;
        this.clock = clock;
        this.logs = logs;
    }

    @Transactional(readOnly = true)
    public List<Item> list() {
        var items = entities.createQuery("from TreatmentItem order by id", TreatmentItem.class).getResultList();
        var histories = new HashMap<Long, List<Price>>();
        jdbc.sql("select item_id, id, effective_from, unit_price from standard_price order by item_id, effective_from")
            .query(rs -> {
                while (rs.next()) {
                    histories.computeIfAbsent(rs.getLong("item_id"), ignored -> new ArrayList<>())
                        .add(new Price(rs.getLong("id"), rs.getObject("effective_from", LocalDate.class),
                            rs.getBigDecimal("unit_price")));
                }
                return histories;
            });
        LocalDate today = LocalDate.now(clock);
        return items.stream().map(item -> view(item, histories.getOrDefault(item.id, List.of()), today)).toList();
    }

    @Transactional(readOnly = true)
    public Item get(long id) {
        var item = entities.find(TreatmentItem.class, id);
        if (item == null) throw BusinessException.notFound();
        return view(item, false);
    }

    public Item create(Create input) {
        var item = new TreatmentItem();
        item.setDetails(input.name(), input.standardUnit(), input.quantityScale());
        entities.persist(item);
        insertPrice(item.id, input.firstPrice());
        var result = view(item, true);
        logs.record("CREATE", "TREATMENT_ITEM", item.id, result);
        return result;
    }

    public Item update(long id, Edit input) {
        var item = VersionedWrites.lock(entities, TreatmentItem.class, id);
        var before = VersionedWrites.check(input.version(), item.version, () -> view(item, true));
        item.setDetails(input.name(), input.standardUnit(), input.quantityScale());
        item.active = input.active();
        VersionedWrites.advance(entities, item, input.version(), () -> item.version);
        var result = view(item, true);
        logs.record("UPDATE", "TREATMENT_ITEM", id, Map.of("before", before, "after", result));
        return result;
    }

    public Item addPrice(long id, PriceInput input) {
        var item = VersionedWrites.lock(entities, TreatmentItem.class, id);
        var before = view(item, true);
        insertPrice(id, input);
        VersionedWrites.advance(entities, item, before.version(), () -> item.version);
        var result = view(item, true);
        logs.record("ADD_STANDARD_PRICE", "TREATMENT_ITEM", id, Map.of("before", before, "after", result));
        return result;
    }

    private void insertPrice(long itemId, PriceInput input) {
        jdbc.sql("insert into standard_price (item_id, effective_from, unit_price) values (?, ?, ?)")
            .params(itemId, input.effectiveFrom(), input.unitPrice()).update();
    }

    private Item view(TreatmentItem item, boolean locked) {
        // Match the locked parent's revision even if a repeatable-read snapshot predates the lock.
        String query = "select id, effective_from, unit_price from standard_price where item_id = ? order by effective_from";
        var prices = jdbc.sql(locked ? query + " for share" : query)
            .param(item.id).query((rs, row) -> new Price(rs.getLong("id"),
                rs.getObject("effective_from", LocalDate.class), rs.getBigDecimal("unit_price"))).list();
        return view(item, prices, LocalDate.now(clock));
    }

    private static Item view(TreatmentItem item, List<Price> prices, LocalDate today) {
        BigDecimal currentPrice = null;
        for (Price price : prices) {
            if (price.effectiveFrom().isAfter(today)) break;
            currentPrice = price.unitPrice();
        }
        return new Item(item.id, item.name, item.standardUnit, item.quantityScale, item.active,
            item.version, currentPrice, prices);
    }
}
