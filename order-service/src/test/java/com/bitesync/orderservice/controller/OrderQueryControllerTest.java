package com.bitesync.orderservice.controller;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.orderservice.config.SecurityConfig;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.dto.OrderItemResponse;
import com.bitesync.orderservice.dto.OrderSummaryResponse;
import com.bitesync.orderservice.model.OrderStatus;
import com.bitesync.orderservice.service.OrderQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = OrderQueryController.class)
@Import(SecurityConfig.class)
class OrderQueryControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockBean private OrderQueryService orderQueryService;
    @MockBean private JwtDecoder jwtDecoder;

    private UUID buyerId;
    private UUID orderId;
    private OrderSummaryResponse sampleSummary;

    @BeforeEach
    void setUp() {
        buyerId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        sampleSummary = new OrderSummaryResponse(
                orderId, buyerId, "65f1a29b4c8e12001a9c4b11", "Spice Route Biryani",
                OrderStatus.CONFIRMED, new BigDecimal("698.00"), 2, null,
                List.of(new OrderItemResponse("item-101", "Hyderabadi Chicken Dum Biryani", 2, new BigDecimal("349.00"))),
                Instant.now(), Instant.now()
        );
    }

    @Test
    @DisplayName("GET /api/orders - Authenticated buyer fetches own CQRS summaries")
    void getMyOrders_Success() throws Exception {
        when(orderQueryService.getOrdersForCustomer(buyerId.toString())).thenReturn(List.of(sampleSummary));

        mockMvc.perform(get("/api/orders")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString())))
                        .header(BiteSyncCommonConstants.HEADER_TRACE_ID, "trace-q-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-q-1"))
                .andExpect(jsonPath("$.message").value(OrderConstants.MSG_ORDERS_FETCHED))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[0].totalAmount").value(698.0));
    }

    @Test
    @DisplayName("GET /api/orders - Unauthenticated caller blocked with 401")
    void getMyOrders_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderQueryService);
    }

    @Test
    @DisplayName("GET /api/orders/{id} - Owning CUSTOMER passes isAdmin=false and receives 200 OK")
    void getOrderById_OwnerSuccess() throws Exception {
        when(orderQueryService.getOrderById(orderId.toString(), buyerId.toString(), false))
                .thenReturn(sampleSummary);

        mockMvc.perform(get("/api/orders/{id}", orderId.toString())
                        .with(jwt()
                                .jwt(j -> j.subject(buyerId.toString()))
                                .authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(OrderConstants.MSG_ORDER_FETCHED))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.itemCount").value(2));
    }

    @Test
    @DisplayName("GET /api/orders/{id} - ADMIN passes isAdmin=true and succeeds with 200 OK")
    void getOrderById_AdminOverrideSuccess() throws Exception {
        String adminId = UUID.randomUUID().toString();
        when(orderQueryService.getOrderById(orderId.toString(), adminId, true))
                .thenReturn(sampleSummary);

        mockMvc.perform(get("/api/orders/{id}", orderId.toString())
                        .with(jwt()
                                .jwt(j -> j.subject(adminId))
                                .authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()));
    }

    @Test
    @DisplayName("GET /api/orders/{id} - Cross-customer read maps AccessDeniedException to 403 FORBIDDEN_ACCESS")
    void getOrderById_CrossCustomerForbidden() throws Exception {
        String otherUser = UUID.randomUUID().toString();
        when(orderQueryService.getOrderById(orderId.toString(), otherUser, false))
                .thenThrow(new AccessDeniedException(OrderConstants.MSG_ORDER_ACCESS_DENIED));

        mockMvc.perform(get("/api/orders/{id}", orderId.toString())
                        .with(jwt()
                                .jwt(j -> j.subject(otherUser))
                                .authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(BiteSyncCommonConstants.ERROR_FORBIDDEN));
    }

    @Test
    @DisplayName("GET /api/orders/{id} - Maps ORDER_002 (bad UUID) and ORDER_001 (not found) to 400")
    void getOrderById_InvalidAndNotFound() throws Exception {
        when(orderQueryService.getOrderById("bad-uuid", buyerId.toString(), false))
                .thenThrow(new BiteSyncException(OrderConstants.MSG_INVALID_ORDER_ID, OrderConstants.ERROR_INVALID_ORDER_REQUEST));

        mockMvc.perform(get("/api/orders/{id}", "bad-uuid")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(OrderConstants.ERROR_INVALID_ORDER_REQUEST));

        when(orderQueryService.getOrderById(orderId.toString(), buyerId.toString(), false))
                .thenThrow(new BiteSyncException(OrderConstants.MSG_ORDER_NOT_FOUND, OrderConstants.ERROR_ORDER_NOT_FOUND));

        mockMvc.perform(get("/api/orders/{id}", orderId.toString())
                        .with(jwt().jwt(j -> j.subject(buyerId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(OrderConstants.ERROR_ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("SecurityConfig JwtAuthenticationConverter maps 'roles' claim list to ROLE_ authorities")
    void jwtAuthenticationConverter_MapsRolesClaim() {
        Jwt sampleJwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(buyerId.toString())
                .claim("roles", List.of("CUSTOMER", "ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var authToken = jwtAuthenticationConverter.convert(sampleJwt);
        assertNotNull(authToken);
        List<String> authorities = authToken.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertEquals(2, authorities.size());
        assertTrue(authorities.containsAll(List.of("ROLE_CUSTOMER", "ROLE_ADMIN")));
    }
}