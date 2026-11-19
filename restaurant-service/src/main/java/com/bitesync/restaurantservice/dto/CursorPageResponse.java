package com.bitesync.restaurantservice.dto;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> items,
        int size,
        boolean hasNext,
        Double nextRatingCursor,
        String nextIdCursor
) {}