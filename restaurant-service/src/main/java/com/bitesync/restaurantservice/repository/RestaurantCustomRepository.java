package com.bitesync.restaurantservice.repository;

import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;

import java.util.List;

public interface RestaurantCustomRepository {

    /**
     * Fetches up to (limit + 1) restaurants matching optional city + status,
     * sorted by rating DESC, _id DESC, starting strictly after (afterRating, afterId).
     */
    List<Restaurant> findByCursor(
            String cityKey,
            RestaurantStatus status,
            Double afterRating,
            String afterId,
            int limit
    );
}