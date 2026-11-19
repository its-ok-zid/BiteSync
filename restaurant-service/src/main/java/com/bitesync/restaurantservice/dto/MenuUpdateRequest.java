package com.bitesync.restaurantservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record MenuUpdateRequest(
        @NotNull(message = "Menu categories cannot be null")
        @Valid
        List<MenuCategoryDto> menuCategories
) {}