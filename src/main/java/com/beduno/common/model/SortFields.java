package com.beduno.common.model;

import com.beduno.common.exception.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

/**
 * Swaps the sort fields of a request for the column names a native query can order by.
 *
 * <p>Spring Data appends a {@link Pageable}'s sort straight into native SQL, so whatever a client
 * sends has to already BE a column. Clients naturally sort by the names they see in the JSON --
 * {@code lastName} -- which Postgres folds to {@code lastname}, and no such column exists, so the
 * whole request failed with a 500. The endpoint only ever worked because its default said
 * {@code last_name}, leaking the database's naming into the API.
 *
 * <p>Sorting is not an injection risk either way: Spring Data rejects a sort expression that is not
 * a plain property reference before it reaches the database. The list here is about a stable API
 * contract and a decent error -- an unsupported field is the caller's mistake, so it earns a 400
 * rather than a stack trace.
 */
public final class SortFields {

    private SortFields() {
    }

    /**
     * @param columnsByField the sortable API field names, each mapped to its column
     */
    public static Pageable toColumns(Pageable pageable, Map<String, String> columnsByField) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }
        var orders = pageable.getSort().stream()
                .map(order -> {
                    var column = columnsByField.get(order.getProperty());
                    if (column == null) {
                        throw new ValidationException("error.sort.unsupported_field");
                    }
                    return new Sort.Order(order.getDirection(), column, order.getNullHandling());
                })
                .toList();
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }
}
