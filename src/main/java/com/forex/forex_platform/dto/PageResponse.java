package com.forex.forex_platform.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Stable JSON shape for paginated lists (Spring's Page isn't meant to be serialized directly). */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
