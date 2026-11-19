package com.bitesync.restaurantservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record MenuItemDto(
        String itemId,

        @NotBlank(message = "Menu item name cannot be blank")
        String name,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.01", message = "Price must be greater than 0")
        BigDecimal price,

        @JsonProperty("isAvailable")
        Boolean available,

        List<@NotBlank(message = "Addon cannot be blank") String> addons
) {}