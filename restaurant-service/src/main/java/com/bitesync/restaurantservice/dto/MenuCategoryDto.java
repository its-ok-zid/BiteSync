package com.bitesync.restaurantservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record MenuCategoryDto(
        @NotBlank(message = "Category name cannot be blank")
        String name,

        @NotNull(message = "Category items list cannot be null")
        @Valid
        List<MenuItemDto> items
) {}