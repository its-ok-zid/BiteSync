package com.bitesync.restaurantservice.controller;

import com.bitesync.common.dto.ApiResponse;
import com.bitesync.restaurantservice.dto.CursorPageResponse;
import com.bitesync.restaurantservice.dto.L1CacheStatsResponse;
import com.bitesync.restaurantservice.dto.MenuUpdateRequest;
import com.bitesync.restaurantservice.dto.RestaurantApplyRequest;
import com.bitesync.restaurantservice.dto.RestaurantResponse;
import com.bitesync.restaurantservice.dto.RestaurantSummaryResponse;
import com.bitesync.restaurantservice.service.RestaurantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/restaurants")
public class RestaurantController {

    private final RestaurantService restaurantService;

    public RestaurantController(RestaurantService restaurantService) {
        this.restaurantService = restaurantService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CursorPageResponse<RestaurantSummaryResponse>>> browseRestaurants(
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "afterRating", required = false) Double afterRating,
            @RequestParam(name = "afterId", required = false) String afterId,
            @RequestParam(name = "size", defaultValue = "10") int size,
            HttpServletRequest httpRequest) {

        CursorPageResponse<RestaurantSummaryResponse> page =
                restaurantService.browseActiveRestaurants(city, afterRating, afterId, size);

        ApiResponse<CursorPageResponse<RestaurantSummaryResponse>> response = new ApiResponse<>(
                resolveTraceId(httpRequest),
                "Restaurants fetched successfully",
                page
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RestaurantResponse>> getRestaurantById(
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {

        RestaurantResponse data = restaurantService.getActiveRestaurantById(id);
        ApiResponse<RestaurantResponse> response = new ApiResponse<>(
                resolveTraceId(httpRequest),
                "Restaurant fetched successfully",
                data
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/apply")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RestaurantResponse>> applyForRestaurant(
            @Valid @RequestBody RestaurantApplyRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {

        // Golden Rule: Always bind ownerId from verified JWT 'sub' claim, never from JSON body
        String ownerId = jwt.getSubject();
        RestaurantResponse created = restaurantService.applyForRestaurant(request, ownerId);

        ApiResponse<RestaurantResponse> response = new ApiResponse<>(
                resolveTraceId(httpRequest),
                "Restaurant application submitted successfully",
                created
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RestaurantResponse>> approveRestaurant(
            @PathVariable("id") String id,
            HttpServletRequest httpRequest) {

        RestaurantResponse approved = restaurantService.approveRestaurant(id);
        ApiResponse<RestaurantResponse> response = new ApiResponse<>(
                resolveTraceId(httpRequest),
                "Restaurant approved successfully",
                approved
        );
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/menu")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<RestaurantResponse>> updateMenu(
            @PathVariable("id") String id,
            @Valid @RequestBody MenuUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {

        RestaurantResponse updated = restaurantService.updateMenu(id, request, jwt.getSubject());
        ApiResponse<RestaurantResponse> response = new ApiResponse<>(
                resolveTraceId(httpRequest),
                "Restaurant menu updated successfully",
                updated
        );
        return ResponseEntity.ok(response);
    }

    // Dev-only inspection endpoint to view live L1 Caffeine RAM state in Chrome
    @GetMapping("/cache/l1-stats")
    public ResponseEntity<ApiResponse<L1CacheStatsResponse>> getL1CacheStats(HttpServletRequest httpRequest) {
        ApiResponse<L1CacheStatsResponse> response = new ApiResponse<>(
                resolveTraceId(httpRequest),
                "L1 Caffeine cache snapshot fetched",
                restaurantService.getL1CacheSnapshot()
        );
        return ResponseEntity.ok(response);
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-B3-TraceId");
        return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
    }
}