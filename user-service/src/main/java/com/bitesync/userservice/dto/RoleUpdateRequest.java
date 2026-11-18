package com.bitesync.userservice.dto;

import com.bitesync.userservice.model.Role;
import jakarta.validation.constraints.NotNull;

public record RoleUpdateRequest(
        @NotNull(message = "Role is required")
        Role role
) {}