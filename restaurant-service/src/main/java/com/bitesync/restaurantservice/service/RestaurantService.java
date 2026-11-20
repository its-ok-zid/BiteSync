package com.bitesync.restaurantservice.service;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.restaurantservice.cache.RestaurantCacheService;
import com.bitesync.restaurantservice.dto.*;
import com.bitesync.restaurantservice.model.MenuCategory;
import com.bitesync.restaurantservice.model.MenuItem;
import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;
import com.bitesync.restaurantservice.repository.RestaurantRepository;
import org.bson.types.ObjectId;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RestaurantService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 50;

    private final RestaurantRepository restaurantRepository;
    private final RestaurantCacheService restaurantCacheService;

    public RestaurantService(
            RestaurantRepository restaurantRepository,
            RestaurantCacheService restaurantCacheService) {
        this.restaurantRepository = restaurantRepository;
        this.restaurantCacheService = restaurantCacheService;
    }
    /**
     * Step 1 of Onboarding: Any authenticated user applies to register a restaurant.
     * Owner is strictly bound to the verified JWT 'sub' claim, and status starts as PENDING_APPROVAL.
     */
    public RestaurantResponse applyForRestaurant(RestaurantApplyRequest request, String ownerId) {
        Restaurant restaurant = new Restaurant();
        restaurant.setOwnerId(ownerId);
        restaurant.setName(request.name().trim());
        restaurant.setCity(request.city());
        restaurant.setCuisineTypes(
                request.cuisineTypes().stream()
                        .map(String::trim)
                        .distinct()
                        .toList()
        );
        restaurant.setStatus(RestaurantStatus.PENDING_APPROVAL);
        restaurant.setRating(0.0);
        restaurant.setMenuCategories(new ArrayList<>());

        Restaurant saved = restaurantRepository.save(restaurant);
        return RestaurantResponse.from(saved);
    }

    /**
     * Step 2 of Onboarding: ADMIN activates a pending restaurant.
     * (Step 4 will add L2 Redis eviction + Redis Pub/Sub L1 invalidation here.)
     */
    public RestaurantResponse approveRestaurant(String restaurantId) {
        validateObjectId(restaurantId);

        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new BiteSyncException("Restaurant not found", "REST_001"));

        if (restaurant.getStatus() == RestaurantStatus.ACTIVE) {
            throw new BiteSyncException("Restaurant is already active", "REST_004");
        }

        restaurant.setStatus(RestaurantStatus.ACTIVE);
        Restaurant saved = restaurantRepository.save(restaurant);

        // Clears any 30s negative cache sentinel across L2 + all pods' L1
        restaurantCacheService.evict(restaurantId);

        return RestaurantResponse.from(saved);
    }

    /**
     * Step 3 of Onboarding: Approved RESTAURANT_OWNER updates their embedded menu.
     * Enforces strict ownership check against JWT 'sub' and requires ACTIVE status.
     * (Step 4 will add L2 Redis eviction + Redis Pub/Sub L1 invalidation here.)
     */
    public RestaurantResponse updateMenu(String restaurantId, MenuUpdateRequest request, String authenticatedUserId) {
        validateObjectId(restaurantId);

        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new BiteSyncException("Restaurant not found", "REST_001"));

        if (!restaurant.getOwnerId().equals(authenticatedUserId)) {
            throw new AccessDeniedException("You do not own this restaurant");
        }

        if (restaurant.getStatus() != RestaurantStatus.ACTIVE) {
            throw new BiteSyncException("Cannot update menu until restaurant is ACTIVE", "REST_005");
        }

        List<MenuCategory> mappedCategories = request.menuCategories().stream()
                .map(this::toMenuCategory)
                .toList();

        restaurant.setMenuCategories(mappedCategories);
        Restaurant saved = restaurantRepository.save(restaurant);

        // Evict-on-write: delete L2 + local L1 + broadcast Pub/Sub invalidation
        restaurantCacheService.evict(restaurantId);

        return RestaurantResponse.from(saved);
    }

    /**
     * Public Read (Single Restaurant + Full Menu):
     * Rejects malformed ObjectIds before hitting MongoDB and hides non-ACTIVE restaurants.
     * (Step 4 will wrap this with L1 Caffeine -> L2 Redis -> MongoDB + Negative Caching.)
     */
    public RestaurantResponse getActiveRestaurantById(String restaurantId) {
        validateObjectId(restaurantId);

        return restaurantCacheService.getActiveRestaurant(
                restaurantId,
                id -> restaurantRepository.findById(id)
                        .filter(r -> r.getStatus() == RestaurantStatus.ACTIVE)
                        .map(RestaurantResponse::from)
        ).orElseThrow(() -> new BiteSyncException("Restaurant not found", "REST_001"));
    }

    /**
     * Public Browse (Composite Cursor Pagination):
     * Uses ESR compound index { cityKey: 1, status: 1, rating: -1, _id: -1 } with (limit + 1) trick.
     */
    public CursorPageResponse<RestaurantSummaryResponse> browseActiveRestaurants(
            String city,
            Double afterRating,
            String afterId,
            int size) {

        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new BiteSyncException("Page size must be between 1 and 50", "REST_002");
        }

        boolean hasRating = afterRating != null;
        boolean hasId = afterId != null && !afterId.isBlank();
        if (hasRating != hasId) {
            throw new BiteSyncException(
                    "Both afterRating and afterId must be provided together for cursor pagination",
                    "REST_002"
            );
        }

        String normalizedCityKey = (city != null && !city.isBlank()) ? city.trim().toLowerCase() : null;

        List<Restaurant> fetched = restaurantRepository.findByCursor(
                normalizedCityKey,
                RestaurantStatus.ACTIVE,
                afterRating,
                afterId,
                size
        );

        boolean hasNext = fetched.size() > size;
        List<Restaurant> pageItems = hasNext ? fetched.subList(0, size) : fetched;

        Double nextRatingCursor = null;
        String nextIdCursor = null;
        if (hasNext && !pageItems.isEmpty()) {
            Restaurant last = pageItems.get(pageItems.size() - 1);
            nextRatingCursor = last.getRating();
            nextIdCursor = last.getId();
        }

        List<RestaurantSummaryResponse> summaries = pageItems.stream()
                .map(RestaurantSummaryResponse::from)
                .toList();

        return new CursorPageResponse<>(summaries, summaries.size(), hasNext, nextRatingCursor, nextIdCursor);
    }

    /**
     * Delegates to RestaurantCacheService to inspect live L1 Caffeine RAM state.
     */
    public L1CacheStatsResponse getL1CacheSnapshot() {
        return restaurantCacheService.getL1Snapshot();
    }

    private void validateObjectId(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new BiteSyncException("Invalid restaurant ID format", "REST_002");
        }
    }

    private MenuCategory toMenuCategory(MenuCategoryDto dto) {
        List<MenuItem> items = dto.items().stream()
                .map(itemDto -> new MenuItem(
                        (itemDto.itemId() != null && !itemDto.itemId().isBlank())
                                ? itemDto.itemId().trim()
                                : "item-" + UUID.randomUUID().toString().substring(0, 8),
                        itemDto.name().trim(),
                        itemDto.price(),
                        itemDto.available() == null || itemDto.available(),
                        itemDto.addons() != null
                                ? itemDto.addons().stream().map(String::trim).toList()
                                : new ArrayList<>()
                ))
                .toList();

        return new MenuCategory(dto.name().trim(), items);
    }
}