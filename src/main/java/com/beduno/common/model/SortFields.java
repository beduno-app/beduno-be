package com.beduno.common.model;

import com.beduno.common.exception.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

/**
 * Maps the sort fields of a request onto the names the underlying query can actually order by.
 *
 * <p>What that target is depends on the query. A native query receives the sort appended straight
 * into its SQL, so the name has to BE a column: clients sort by the names they see in the JSON --
 * {@code lastName} -- which Postgres folds to {@code lastname}, no such column exists, and the
 * request died with a 500. Those endpoints only ever worked because their defaults named columns,
 * leaking the database's naming into the API. A derived or JPQL query instead resolves the sort
 * against the entity, so the target is a property name and an unknown one raises
 * PropertyReferenceException -- a different failure, the same 500 for the caller.
 *
 * <p>Either way the caller's mistake earns a 400 rather than a stack trace, and the map doubles as
 * the endpoint's documented sortable surface. Sorting was never an injection risk: Spring Data
 * rejects a sort expression that is not a plain property reference before it reaches the database.
 */
public final class SortFields {

    private SortFields() {
    }

    /**
     * @param targetsByField the sortable API field names, each mapped to the column or property
     *                       the backing query orders by
     */
    public static Pageable translate(Pageable pageable, Map<String, String> targetsByField) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }
        var orders = pageable.getSort().stream()
                .map(order -> {
                    var target = targetsByField.get(order.getProperty());
                    if (target == null) {
                        throw new ValidationException("error.sort.unsupported_field");
                    }
                    return new Sort.Order(order.getDirection(), target, order.getNullHandling());
                })
                .toList();
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }
}
