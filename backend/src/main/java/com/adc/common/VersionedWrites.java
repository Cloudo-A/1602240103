package com.adc.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class VersionedWrites {
    private VersionedWrites() {}

    public static <E> E lock(EntityManager em, Class<E> type, long id) {
        E entity = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) throw BusinessException.notFound();
        return entity;
    }

    public static <V> V check(Integer expected, int actual, Supplier<V> currentView) {
        if (expected == null) throw BusinessException.invalid("version", "请提供版本号");
        V current = currentView.get();
        if (actual != expected) throw BusinessException.changed(current);
        return current;
    }

    public static void advance(EntityManager em, Object entity, int before, IntSupplier version) {
        em.flush();
        // 子表变更和重复保存也会使客户端持有的旧版本失效。
        if (version.getAsInt() == before) em.lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }
}
