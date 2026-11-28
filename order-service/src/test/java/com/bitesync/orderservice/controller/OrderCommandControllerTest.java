package com.bitesync.orderservice.controller;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.orderservice.config.SecurityConfig;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.dto.OrderItemRequest;
import com.bitesync.orderservice.dto.OrderItemResponse;
import com.bitesync.orderservice.dto.OrderResponse;
import com.bitesync.orderservice.dto.PlaceOrderRequest;
import com.bitesync.orderservice.dto.PlaceOrderResult;
import com.bitesync.orderservice.model.OrderStatus;
import com.bitesync.orderservice.service.OrderCommandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = OrderCommandController.class)
@Import(SecurityConfig.class)
class OrderCommandControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private OrderCommandService orderCommandService;
    @MockBean private JwtDecoder jwtDecoder;

    private static final String REST_ID = "65f1a29b4c8e12001a9c4b11";
    private UUID buyerId;
    private UUID orderId;
    private OrderResponse sampleOrder;

    @BeforeEach
    void setUp() {
        buyerId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        sampleOrder = new OrderResponse(
                orderId, buyerId, REST_ID, null,
                OrderStatus.PENDING, BigDecimal.ZERO.setScale(2), null,
                List.of(new OrderItemResponse("item-101", null, 2, BigDecimal.ZERO.setScale(2))),
                Instant.now(), Instant.now()
        );
    }

    @Test
    @DisplayName("POST /api/orders - Fresh order returns 201 Created without X-Idempotent-Replay and preserves traceId")
    void placeOrder_FreshSuccess() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(REST_ID, List.of(new OrderItemRequest("item-101", 2)));
        when(orderCommandService.placeOrder(any(PlaceOrderRequest.class), eq(buyerId.toString()), eq("key-1")))
                .thenReturn(new PlaceOrderResult(sampleOrder, 201, false));

        mockMvc.perform(post("/api/orders")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString())))
                        .header(BiteSyncCommonConstants.HEADER_IDEMPOTENCY_KEY, "key-1")
                        .header(BiteSyncCommonConstants.HEADER_TRACE_ID, "trace-ord-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(BiteSyncCommonConstants.HEADER_IDEMPOTENT_REPLAY))
                .andExpect(jsonPath("$.traceId").value("trace-ord-1"))
                .andExpect(jsonPath("$.message").value(OrderConstants.MSG_ORDER_PLACED))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.totalAmount").value(0.0));
    }

    @Test
    @DisplayName("POST /api/orders - Idempotent replay returns 201 Created with X-Idempotent-Replay: true")
    void placeOrder_IdempotentReplay() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(REST_ID, List.of(new OrderItemRequest("item-101", 2)));
        when(orderCommandService.placeOrder(any(PlaceOrderRequest.class), eq(buyerId.toString()), eq("key-1")))
                .thenReturn(new PlaceOrderResult(sampleOrder, 201, true));

        mockMvc.perform(post("/api/orders")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString())))
                        .header(BiteSyncCommonConstants.HEADER_IDEMPOTENCY_KEY, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string(BiteSyncCommonConstants.HEADER_IDEMPOTENT_REPLAY, "true"))
                .andExpect(jsonPath("$.message").value(OrderConstants.MSG_ORDER_REPLAYED))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()));
    }

    @Test
    @DisplayName("POST /api/orders - Unauthenticated caller blocked with 401 Unauthorized")
    void placeOrder_Unauthenticated() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(REST_ID, List.of(new OrderItemRequest("item-101", 2)));

        mockMvc.perform(post("/api/orders")
                        .header(BiteSyncCommonConstants.HEADER_IDEMPOTENCY_KEY, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderCommandService);
    }

    @Test
    @DisplayName("POST /api/orders - @Valid rejects zero quantity with 400 VALIDATION_ERROR")
    void placeOrder_ValidationError() throws Exception {
        PlaceOrderRequest invalid = new PlaceOrderRequest(REST_ID, List.of(new OrderItemRequest("item-101", 0)));

        mockMvc.perform(post("/api/orders")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString())))
                        .header(BiteSyncCommonConstants.HEADER_IDEMPOTENCY_KEY, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(BiteSyncCommonConstants.ERROR_VALIDATION));

        verifyNoInteractions(orderCommandService);
    }

    @Test
    @DisplayName("POST /api/orders - Missing Idempotency-Key mapped to 400 ORDER_002")
    void placeOrder_MissingIdempotencyKey() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(REST_ID, List.of(new OrderItemRequest("item-101", 2)));
        when(orderCommandService.placeOrder(any(PlaceOrderRequest.class), eq(buyerId.toString()), isNull()))
                .thenThrow(new BiteSyncException(
                        OrderConstants.MSG_IDEMPOTENCY_KEY_REQUIRED,
                        OrderConstants.ERROR_INVALID_ORDER_REQUEST
                ));

        mockMvc.perform(post("/api/orders")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(OrderConstants.ERROR_INVALID_ORDER_REQUEST));
    }

    @Test
    @DisplayName("POST /api/orders - Idempotency payload conflict mapped to 400 ORDER_003")
    void placeOrder_IdempotencyConflict() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(REST_ID, List.of(new OrderItemRequest("item-101", 5)));
        when(orderCommandService.placeOrder(any(PlaceOrderRequest.class), eq(buyerId.toString()), eq("key-1")))
                .thenThrow(new BiteSyncException(
                        OrderConstants.MSG_IDEMPOTENCY_CONFLICT,
                        OrderConstants.ERROR_IDEMPOTENCY_CONFLICT
                ));

        mockMvc.perform(post("/api/orders")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString())))
                        .header(BiteSyncCommonConstants.HEADER_IDEMPOTENCY_KEY, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(OrderConstants.ERROR_IDEMPOTENCY_CONFLICT));
    }
}