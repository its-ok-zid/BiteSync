package com.bitesync.restaurantservice.controller;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.restaurantservice.config.SecurityConfig;
import com.bitesync.restaurantservice.dto.*;
import com.bitesync.restaurantservice.model.MenuCategory;
import com.bitesync.restaurantservice.model.MenuItem;
import com.bitesync.restaurantservice.model.RestaurantStatus;
import com.bitesync.restaurantservice.service.RestaurantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = RestaurantController.class)
@Import(SecurityConfig.class)
class RestaurantControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockBean private RestaurantService restaurantService;
    @MockBean private JwtDecoder jwtDecoder;

    private static final String REST_ID = "65f1a29b4c8e12001a9c4b11";
    private static final String OWNER_ID = "d290f1ee-6c54-4b01-90e6-d701748f0851";

    private RestaurantResponse activeResponse;
    private RestaurantResponse pendingResponse;

    @BeforeEach
    void setUp() {
        MenuItem item = new MenuItem("item-101", "Chicken Biryani", new BigDecimal("320.00"), true, List.of("Raita"));
        activeResponse = new RestaurantResponse(
                REST_ID, OWNER_ID, "Spice Route Biryani", "Bengaluru",
                List.of("Biryani"), RestaurantStatus.ACTIVE, 4.7,
                List.of(new MenuCategory("Biryanis", List.of(item))),
                Instant.now(), Instant.now()
        );
        pendingResponse = new RestaurantResponse(
                REST_ID, OWNER_ID, "Spice Route Biryani", "Bengaluru",
                List.of("Biryani"), RestaurantStatus.PENDING_APPROVAL, 0.0,
                List.of(), Instant.now(), Instant.now()
        );
    }

    // ==================== 1. GET /api/restaurants ====================

    @Test
    @DisplayName("GET /api/restaurants - Public browse returns cursor page and preserves X-B3-TraceId")
    void browseRestaurants_PublicSuccess() throws Exception {
        RestaurantSummaryResponse summary = new RestaurantSummaryResponse(
                REST_ID, "Spice Route Biryani", "Bengaluru",
                List.of("Biryani"), RestaurantStatus.ACTIVE, 4.7
        );
        CursorPageResponse<RestaurantSummaryResponse> page =
                new CursorPageResponse<>(List.of(summary), 1, true, 4.7, REST_ID);

        when(restaurantService.browseActiveRestaurants("Bengaluru", null, null, 1)).thenReturn(page);

        mockMvc.perform(get("/api/restaurants")
                        .header("X-B3-TraceId", "trace-browse-1")
                        .param("city", "Bengaluru")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-browse-1"))
                .andExpect(jsonPath("$.message").value("Restaurants fetched successfully"))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextRatingCursor").value(4.7))
                .andExpect(jsonPath("$.data.nextIdCursor").value(REST_ID))
                .andExpect(jsonPath("$.data.items[0].name").value("Spice Route Biryani"));
    }

    // ==================== 2. GET /api/restaurants/{id} ====================

    @Test
    @DisplayName("GET /api/restaurants/{id} - Public single lookup returns full embedded menu")
    void getRestaurantById_PublicSuccess() throws Exception {
        when(restaurantService.getActiveRestaurantById(REST_ID)).thenReturn(activeResponse);

        mockMvc.perform(get("/api/restaurants/{id}", REST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data.id").value(REST_ID))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.menuCategories[0].items[0].isAvailable").value(true));
    }

    @Test
    @DisplayName("GET /api/restaurants/{id} - Maps BiteSyncException REST_002 to 400 ErrorResponse")
    void getRestaurantById_InvalidObjectId() throws Exception {
        when(restaurantService.getActiveRestaurantById("bad-id"))
                .thenThrow(new BiteSyncException("Invalid restaurant ID format", "REST_002"));

        mockMvc.perform(get("/api/restaurants/{id}", "bad-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REST_002"))
                .andExpect(jsonPath("$.status").value(400));
    }

    // ==================== 3. POST /api/restaurants/apply ====================

    @Test
    @DisplayName("POST /api/restaurants/apply - Authenticated user creates PENDING_APPROVAL restaurant bound to JWT sub")
    void applyForRestaurant_Success() throws Exception {
        RestaurantApplyRequest request = new RestaurantApplyRequest(
                "Spice Route Biryani", "Bengaluru", List.of("Biryani")
        );
        when(restaurantService.applyForRestaurant(any(RestaurantApplyRequest.class), eq(OWNER_ID)))
                .thenReturn(pendingResponse);

        mockMvc.perform(post("/api/restaurants/apply")
                        .with(jwt().jwt(j -> j.subject(OWNER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Restaurant application submitted successfully"))
                .andExpect(jsonPath("$.data.ownerId").value(OWNER_ID))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        verify(restaurantService, times(1)).applyForRestaurant(any(RestaurantApplyRequest.class), eq(OWNER_ID));
    }

    @Test
    @DisplayName("POST /api/restaurants/apply - Returns 401 Unauthorized when unauthenticated")
    void applyForRestaurant_Unauthenticated() throws Exception {
        RestaurantApplyRequest request = new RestaurantApplyRequest("Test", "Bengaluru", List.of("Biryani"));

        mockMvc.perform(post("/api/restaurants/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(restaurantService);
    }

    @Test
    @DisplayName("POST /api/restaurants/apply - Returns 400 VALIDATION_ERROR on invalid fields")
    void applyForRestaurant_ValidationError() throws Exception {
        RestaurantApplyRequest invalid = new RestaurantApplyRequest("A", "", List.of());

        mockMvc.perform(post("/api/restaurants/apply")
                        .with(jwt().jwt(j -> j.subject(OWNER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(restaurantService);
    }

    // ==================== 4. PATCH /api/restaurants/{id}/approve ====================

    @Test
    @DisplayName("PATCH /api/restaurants/{id}/approve - ADMIN approves restaurant (200 OK)")
    @WithMockUser(roles = "ADMIN")
    void approveRestaurant_AdminSuccess() throws Exception {
        when(restaurantService.approveRestaurant(REST_ID)).thenReturn(activeResponse);

        mockMvc.perform(patch("/api/restaurants/{id}/approve", REST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Restaurant approved successfully"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("PATCH /api/restaurants/{id}/approve - CUSTOMER is blocked with 403 FORBIDDEN_ACCESS")
    @WithMockUser(roles = "CUSTOMER")
    void approveRestaurant_CustomerForbidden() throws Exception {
        mockMvc.perform(patch("/api/restaurants/{id}/approve", REST_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));

        verifyNoInteractions(restaurantService);
    }

    @Test
    @DisplayName("PATCH /api/restaurants/{id}/approve - Duplicate approval returns 400 REST_004")
    @WithMockUser(roles = "ADMIN")
    void approveRestaurant_AlreadyActive() throws Exception {
        when(restaurantService.approveRestaurant(REST_ID))
                .thenThrow(new BiteSyncException("Restaurant is already active", "REST_004"));

        mockMvc.perform(patch("/api/restaurants/{id}/approve", REST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REST_004"));
    }

    // ==================== 5. PUT /api/restaurants/{id}/menu ====================

    @Test
    @DisplayName("PUT /api/restaurants/{id}/menu - Owning RESTAURANT_OWNER updates menu (200 OK)")
    void updateMenu_OwnerSuccess() throws Exception {
        MenuUpdateRequest req = new MenuUpdateRequest(List.of(
                new MenuCategoryDto("Biryanis", List.of(
                        new MenuItemDto("item-101", "Chicken Biryani", new BigDecimal("320.00"), true, List.of("Raita"))
                ))
        ));
        when(restaurantService.updateMenu(eq(REST_ID), any(MenuUpdateRequest.class), eq(OWNER_ID)))
                .thenReturn(activeResponse);

        mockMvc.perform(put("/api/restaurants/{id}/menu", REST_ID)
                        .with(jwt()
                                .jwt(j -> j.subject(OWNER_ID).claim("roles", List.of("CUSTOMER", "RESTAURANT_OWNER")))
                                .authorities(() -> "ROLE_CUSTOMER", () -> "ROLE_RESTAURANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Restaurant menu updated successfully"))
                .andExpect(jsonPath("$.data.menuCategories[0].items[0].price").value(320.00));
    }

    @Test
    @DisplayName("PUT /api/restaurants/{id}/menu - CUSTOMER without RESTAURANT_OWNER role gets 403")
    @WithMockUser(roles = "CUSTOMER")
    void updateMenu_CustomerRoleForbidden() throws Exception {
        MenuUpdateRequest req = new MenuUpdateRequest(List.of());

        mockMvc.perform(put("/api/restaurants/{id}/menu", REST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));

        verifyNoInteractions(restaurantService);
    }

    @Test
    @DisplayName("PUT /api/restaurants/{id}/menu - Different RESTAURANT_OWNER triggers 403 FORBIDDEN_ACCESS")
    void updateMenu_WrongOwnerForbidden() throws Exception {
        MenuUpdateRequest req = new MenuUpdateRequest(List.of());
        when(restaurantService.updateMenu(eq(REST_ID), any(MenuUpdateRequest.class), eq("attacker-uuid")))
                .thenThrow(new AccessDeniedException("You do not own this restaurant"));

        mockMvc.perform(put("/api/restaurants/{id}/menu", REST_ID)
                        .with(jwt()
                                .jwt(j -> j.subject("attacker-uuid"))
                                .authorities(() -> "ROLE_RESTAURANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));
    }

    @Test
    @DisplayName("PUT /api/restaurants/{id}/menu - Invalid item price (0.00) rejected with 400 VALIDATION_ERROR")
    void updateMenu_InvalidPriceValidationError() throws Exception {
        MenuUpdateRequest invalid = new MenuUpdateRequest(List.of(
                new MenuCategoryDto("Biryanis", List.of(
                        new MenuItemDto("item-101", "Free Dish", new BigDecimal("0.00"), true, List.of())
                ))
        ));

        mockMvc.perform(put("/api/restaurants/{id}/menu", REST_ID)
                        .with(jwt()
                                .jwt(j -> j.subject(OWNER_ID))
                                .authorities(() -> "ROLE_RESTAURANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(restaurantService);
    }

    // ==================== 6. GET /api/restaurants/cache/l1-stats & Converter ====================

    @Test
    @DisplayName("GET /api/restaurants/cache/l1-stats - Returns L1 Caffeine stats snapshot")
    void getL1CacheStats_Success() throws Exception {
        L1CacheStatsResponse stats = new L1CacheStatsResponse(
                1, 4, 1, "80.00%", 0, Map.of(REST_ID, "ACTIVE_RESTAURANT (Spice Route Biryani)")
        );
        when(restaurantService.getL1CacheSnapshot()).thenReturn(stats);

        mockMvc.perform(get("/api/restaurants/cache/l1-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimatedSize").value(1))
                .andExpect(jsonPath("$.data.hitCount").value(4))
                .andExpect(jsonPath("$.data.hitRate").value("80.00%"));
    }

    @Test
    @DisplayName("SecurityConfig JwtAuthenticationConverter maps 'roles' claim list to ROLE_ authorities")
    void jwtAuthenticationConverter_MapsRolesClaim() {
        Jwt sampleJwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(OWNER_ID)
                .claim("roles", List.of("ADMIN", "RESTAURANT_OWNER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var authToken = jwtAuthenticationConverter.convert(sampleJwt);
        assertNotNull(authToken);
        List<String> authorities = authToken.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertEquals(2, authorities.size());
        assertTrue(authorities.containsAll(List.of("ROLE_ADMIN", "ROLE_RESTAURANT_OWNER")));
    }
}