package com.education.agent.common.api;

import java.util.List;

public record PageData<T>(
        List<T> items,
        long page,
        long size,
        long total,
        long totalPages
) {
    public PageData {
        items = List.copyOf(items);
    }
}
