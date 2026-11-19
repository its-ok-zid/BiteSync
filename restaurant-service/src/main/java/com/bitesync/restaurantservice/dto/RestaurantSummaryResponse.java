package com.bitesync.restaurantservice.dto;

import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;

import java.util.List;

public record RestaurantSummaryResponse(
        String id,
        String name,
        String city,
        List<String> cuisineTypes,
        RestaurantStatus status,
        double rating
) {
    public static RestaurantSummaryResponse from(Restaurant restaurant) {
        return new RestaurantSummaryResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getCity(),
                restaurant.getCuisineTypes(),
                restaurant.getStatus(),
                restaurant.getRating()
        );
    }
}