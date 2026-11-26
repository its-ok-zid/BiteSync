package com.bitesync.restaurantservice.service;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.restaurantservice.cache.RestaurantCacheService;
import com.bitesync.restaurantservice.dto.CursorPageResponse;
import com.bitesync.restaurantservice.dto.L1CacheStatsResponse;
import com.bitesync.restaurantservice.dto.MenuCategoryDto;
import com.bitesync.restaurantservice.dto.MenuItemDto;
import com.bitesync.restaurantservice.dto.MenuUpdateRequest;
import com.bitesync.restaurantservice.dto.RestaurantApplyRequest;
import com.bitesync.restaurantservice.dto.RestaurantResponse;
import com.bitesync.restaurantservice.dto.RestaurantSummaryResponse;
import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;
import com.bitesync.restaurantservice.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private RestaurantCacheService restaurantCacheService;

    @InjectMocks
    private RestaurantService restaurantService;

    private static final String VALID_ID = "65f1a29b4c8e12001a9c4b11";
    private static final String VALID_ID_2 = "65f1a29b4c8e12001a9c4b22";
    private static final String OWNER_ID = "d290f1ee-6c54-4b01-90e6-d701748f0851";

    private Restaurant pendingRestaurant;
    private Restaurant activeRestaurant;

    @BeforeEach
    void setUp() {
        pendingRestaurant = new Restaurant();
        pendingRestaurant.setId(VALID_ID);
        pendingRestaurant.setOwnerId(OWNER_ID);
        pendingRestaurant.setName("Spice Route Biryani");
        pendingRestaurant.setCity("Bengaluru");
        pendingRestaurant.setCuisineTypes(List.of("Biryani"));
        pendingRestaurant.setStatus(RestaurantStatus.PENDING_APPROVAL);
        pendingRestaurant.setRating(0.0);

        activeRestaurant = new Restaurant();
        activeRestaurant.setId(VALID_ID);
        activeRestaurant.setOwnerId(OWNER_ID);
        activeRestaurant.setName("Spice Route Biryani");
        activeRestaurant.setCity("Bengaluru");
        activeRestaurant.setCuisineTypes(List.of("Biryani"));
        activeRestaurant.setStatus(RestaurantStatus.ACTIVE);
        activeRestaurant.setRating(4.7);
    }

    // ==================== applyForRestaurant ====================

    @Test
    @DisplayName("applyForRestaurant - Binds JWT ownerId, trims/deduplicates inputs, sets PENDING_APPROVAL")
    void applyForRestaurant_Success() {
        RestaurantApplyRequest req = new RestaurantApplyRequest(
                "  Spice Route Biryani  ",
                "  Bengaluru  ",
                List.of("Biryani", " North Indian ", "Biryani")
        );

        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> {
            Restaurant r = inv.getArgument(0);
            r.setId(VALID_ID);
            return r;
        });

        RestaurantResponse response = restaurantService.applyForRestaurant(req, OWNER_ID);

        ArgumentCaptor<Restaurant> captor = ArgumentCaptor.forClass(Restaurant.class);
        verify(restaurantRepository, times(1)).save(captor.capture());

        Restaurant saved = captor.getValue();
        assertEquals(OWNER_ID, saved.getOwnerId());
        assertEquals("Spice Route Biryani", saved.getName());
        assertEquals("Bengaluru", saved.getCity());
        assertEquals("bengaluru", saved.getCityKey());
        assertEquals(List.of("Biryani", "North Indian"), saved.getCuisineTypes());
        assertEquals(RestaurantStatus.PENDING_APPROVAL, saved.getStatus());
        assertEquals(0.0, saved.getRating());
        assertEquals(VALID_ID, response.id());
    }

    // ==================== approveRestaurant ====================

    @Test
    @DisplayName("approveRestaurant - Transitions PENDING_APPROVAL to ACTIVE and evicts cache")
    void approveRestaurant_Success() {
        when(restaurantRepository.findById(VALID_ID)).thenReturn(Optional.of(pendingRestaurant));
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        RestaurantResponse result = restaurantService.approveRestaurant(VALID_ID);

        assertEquals(RestaurantStatus.ACTIVE, result.status());
        verify(restaurantRepository, times(1)).save(pendingRestaurant);
        verify(restaurantCacheService, times(1)).evict(VALID_ID);
    }

    @Test
    @DisplayName("approveRestaurant - Throws REST_002 on invalid ObjectId without hitting DB or cache")
    void approveRestaurant_InvalidId() {
        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> restaurantService.approveRestaurant("invalid-id"));

        assertEquals("REST_002", ex.getErrorCode());
        verifyNoInteractions(restaurantRepository, restaurantCacheService);
    }

    @Test
    @DisplayName("approveRestaurant - Throws REST_001 when restaurant does not exist")
    void approveRestaurant_NotFound() {
        when(restaurantRepository.findById(VALID_ID)).thenReturn(Optional.empty());

        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> restaurantService.approveRestaurant(VALID_ID));

        assertEquals("REST_001", ex.getErrorCode());
        verify(restaurantCacheService, never()).evict(any());
    }

    @Test
    @DisplayName("approveRestaurant - Throws REST_004 when restaurant is already ACTIVE")
    void approveRestaurant_AlreadyActive() {
        when(restaurantRepository.findById(VALID_ID)).thenReturn(Optional.of(activeRestaurant));

        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> restaurantService.approveRestaurant(VALID_ID));

        assertEquals("REST_004", ex.getErrorCode());
        verify(restaurantRepository, never()).save(any());
        verify(restaurantCacheService, never()).evict(any());
    }

    // ==================== updateMenu ====================

    @Test
    @DisplayName("updateMenu - Saves embedded menu with auto-generated itemId & defaults, then evicts cache")
    void updateMenu_Success() {
        MenuUpdateRequest req = new MenuUpdateRequest(List.of(
                new MenuCategoryDto("  Biryanis  ", List.of(
                        new MenuItemDto("item-101", " Chicken Biryani ", new BigDecimal("320.00"), false, List.of(" Raita ")),
                        new MenuItemDto(null, "Paneer Biryani", new BigDecimal("270.00"), null, null)
                ))
        ));

        when(restaurantRepository.findById(VALID_ID)).thenReturn(Optional.of(activeRestaurant));
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        RestaurantResponse result = restaurantService.updateMenu(VALID_ID, req, OWNER_ID);

        assertEquals(1, result.menuCategories().size());
        assertEquals("Biryanis", result.menuCategories().get(0).getName());

        var items = result.menuCategories().get(0).getItems();
        assertEquals(2, items.size());
        assertEquals("item-101", items.get(0).getItemId());
        assertEquals("Chicken Biryani", items.get(0).getName());
        assertFalse(items.get(0).isAvailable());
        assertEquals(List.of("Raita"), items.get(0).getAddons());

        assertTrue(items.get(1).getItemId().startsWith("item-"));
        assertTrue(items.get(1).isAvailable()); // null defaults to true
        assertTrue(items.get(1).getAddons().isEmpty());

        verify(restaurantCacheService, times(1)).evict(VALID_ID);
    }

    @Test
    @DisplayName("updateMenu - Throws AccessDeniedException when caller does not own the restaurant")
    void updateMenu_CrossOwnerBlocked() {
        when(restaurantRepository.findById(VALID_ID)).thenReturn(Optional.of(activeRestaurant));

        assertThrows(AccessDeniedException.class, () ->
                restaurantService.updateMenu(VALID_ID, new MenuUpdateRequest(List.of()), "other-owner-uuid")
        );

        verify(restaurantRepository, never()).save(any());
        verify(restaurantCacheService, never()).evict(any());
    }

    @Test
    @DisplayName("updateMenu - Throws REST_005 when restaurant is not ACTIVE")
    void updateMenu_PendingRestaurantBlocked() {
        when(restaurantRepository.findById(VALID_ID)).thenReturn(Optional.of(pendingRestaurant));

        BiteSyncException ex = assertThrows(BiteSyncException.class, () ->
                restaurantService.updateMenu(VALID_ID, new MenuUpdateRequest(List.of()), OWNER_ID)
        );

        assertEquals("REST_005", ex.getErrorCode());
        verify(restaurantRepository, never()).save(any());
        verify(restaurantCacheService, never()).evict(any());
    }

    // ==================== getActiveRestaurantById ====================

    @Test
    @DisplayName("getActiveRestaurantById - Returns ACTIVE restaurant via cache loader")
    @SuppressWarnings("unchecked")
    void getActiveRestaurantById_ActiveSuccess() {
        when(restaurantRepository.findById(VALID_ID)).thenReturn(Optional.of(activeRestaurant));
        when(restaurantCacheService.getActiveRestaurant(eq(VALID_ID), any(Function.class)))
                .thenAnswer(inv -> {
                    Function<String, Optional<RestaurantResponse>> loader = inv.getArgument(1);
                    return loader.apply(inv.getArgument(0));
                });

        RestaurantResponse result = restaurantService.getActiveRestaurantById(VALID_ID);

        assertEquals(VALID_ID, result.id());
        assertEquals(RestaurantStatus.ACTIVE, result.status());
    }

    @Test
    @DisplayName("getActiveRestaurantById - Hides PENDING_APPROVAL restaurant with REST_001")
    @SuppressWarnings("unchecked")
    void getActiveRestaurantById_PendingHidden() {
        when(restaurantRepository.findById(VALID_ID)).thenReturn(Optional.of(pendingRestaurant));
        when(restaurantCacheService.getActiveRestaurant(eq(VALID_ID), any(Function.class)))
                .thenAnswer(inv -> {
                    Function<String, Optional<RestaurantResponse>> loader = inv.getArgument(1);
                    return loader.apply(inv.getArgument(0));
                });

        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> restaurantService.getActiveRestaurantById(VALID_ID));

        assertEquals("REST_001", ex.getErrorCode());
    }

    @Test
    @DisplayName("getActiveRestaurantById - Rejects malformed ObjectId with REST_002 before touching cache")
    void getActiveRestaurantById_InvalidId() {
        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> restaurantService.getActiveRestaurantById("not-an-object-id"));

        assertEquals("REST_002", ex.getErrorCode());
        verifyNoInteractions(restaurantCacheService, restaurantRepository);
    }

    // ==================== browseActiveRestaurants ====================

    @Test
    @DisplayName("browseActiveRestaurants - Computes hasNext=true and composite cursors when size+1 rows returned")
    void browseActiveRestaurants_HasNextPage() {
        Restaurant second = new Restaurant();
        second.setId(VALID_ID_2);
        second.setName("Dakshin Spice");
        second.setCity("Bengaluru");
        second.setStatus(RestaurantStatus.ACTIVE);
        second.setRating(4.5);

        when(restaurantRepository.findByCursor("bengaluru", RestaurantStatus.ACTIVE, null, null, 1))
                .thenReturn(List.of(activeRestaurant, second));

        CursorPageResponse<RestaurantSummaryResponse> page =
                restaurantService.browseActiveRestaurants(" Bengaluru ", null, null, 1);

        assertEquals(1, page.size());
        assertEquals(1, page.items().size());
        assertTrue(page.hasNext());
        assertEquals(4.7, page.nextRatingCursor());
        assertEquals(VALID_ID, page.nextIdCursor());
    }

    @Test
    @DisplayName("browseActiveRestaurants - Sets hasNext=false and null cursors on final page")
    void browseActiveRestaurants_LastPage() {
        when(restaurantRepository.findByCursor(null, RestaurantStatus.ACTIVE, 4.7, VALID_ID, 2))
                .thenReturn(List.of(activeRestaurant));

        CursorPageResponse<RestaurantSummaryResponse> page =
                restaurantService.browseActiveRestaurants("  ", 4.7, VALID_ID, 2);

        assertEquals(1, page.size());
        assertFalse(page.hasNext());
        assertNull(page.nextRatingCursor());
        assertNull(page.nextIdCursor());
    }

    @Test
    @DisplayName("browseActiveRestaurants - Rejects invalid page size (<1 or >50) and partial cursors with REST_002")
    void browseActiveRestaurants_ValidationGuards() {
        assertEquals("REST_002", assertThrows(BiteSyncException.class,
                () -> restaurantService.browseActiveRestaurants("Bengaluru", null, null, 0)).getErrorCode());
        assertEquals("REST_002", assertThrows(BiteSyncException.class,
                () -> restaurantService.browseActiveRestaurants("Bengaluru", null, null, 51)).getErrorCode());
        assertEquals("REST_002", assertThrows(BiteSyncException.class,
                () -> restaurantService.browseActiveRestaurants("Bengaluru", 4.5, null, 10)).getErrorCode());
        assertEquals("REST_002", assertThrows(BiteSyncException.class,
                () -> restaurantService.browseActiveRestaurants("Bengaluru", null, VALID_ID, 10)).getErrorCode());

        verifyNoInteractions(restaurantRepository);
    }

    // ==================== getL1CacheSnapshot ====================

    @Test
    @DisplayName("getL1CacheSnapshot - Delegates directly to RestaurantCacheService")
    void getL1CacheSnapshot_Delegates() {
        L1CacheStatsResponse expected = new L1CacheStatsResponse(1, 5, 1, "83.33%", 0, Map.of(VALID_ID, "ACTIVE"));
        when(restaurantCacheService.getL1Snapshot()).thenReturn(expected);

        assertEquals(expected, restaurantService.getL1CacheSnapshot());
    }
}