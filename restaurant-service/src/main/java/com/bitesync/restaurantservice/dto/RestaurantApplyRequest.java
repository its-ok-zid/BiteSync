package com.bitesync.restaurantservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RestaurantApplyRequest(
        @NotBlank(message = "Restaurant name cannot be blank")
        @Size(min = 2, max = 120, message = "Restaurant name must be between 2 and 120 characters")
        String name,

        @NotBlank(message = "City cannot be blank")
        @Size(min = 2, max = 80, message = "City must be between 2 and 80 characters")
        String city,

        @NotEmpty(message = "At least one cuisine type is required")
        List<@NotBlank(message = "Cuisine type cannot be blank") String> cuisineTypes
) {}