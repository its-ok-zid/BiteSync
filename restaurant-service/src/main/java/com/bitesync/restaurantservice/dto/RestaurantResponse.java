package com.bitesync.restaurantservice.dto;

import com.bitesync.restaurantservice.model.MenuCategory;
import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;

import java.time.Instant;
import java.util.List;

public record RestaurantResponse(
        String id,
        String ownerId,
        String name,
        String city,
        List<String> cuisineTypes,
        RestaurantStatus status,
        double rating,
        List<MenuCategory> menuCategories,
        Instant createdAt,
        Instant updatedAt
) {
    public static RestaurantResponse from(Restaurant restaurant) {
        return new RestaurantResponse(
                restaurant.getId(),
                restaurant.getOwnerId(),
                restaurant.getName(),
                restaurant.getCity(),
                restaurant.getCuisineTypes(),
                restaurant.getStatus(),
                restaurant.getRating(),
                restaurant.getMenuCategories(),
                restaurant.getCreatedAt(),
                restaurant.getUpdatedAt()
        );
    }
}