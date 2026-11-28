package com.bitesync.orderservice.service;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.dto.OrderItemResponse;
import com.bitesync.orderservice.dto.OrderSummaryResponse;
import com.bitesync.orderservice.model.OrderStatus;
import com.bitesync.orderservice.model.OrderSummaryView;
import com.bitesync.orderservice.repository.OrderSummaryViewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {

    @Mock private OrderSummaryViewRepository viewRepository;

    private ObjectMapper objectMapper;
    private OrderQueryService queryService;

    private UUID orderId;
    private UUID buyerId;
    private OrderSummaryView sampleView;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        queryService = new OrderQueryService(viewRepository, objectMapper);

        orderId = UUID.randomUUID();
        buyerId = UUID.randomUUID();

        sampleView = new OrderSummaryView();
        sampleView.setOrderId(orderId);
        sampleView.setCustomerId(buyerId);
        sampleView.setRestaurantId("65f1a29b4c8e12001a9c4b11");
        sampleView.setRestaurantName("Spice Route Biryani");
        sampleView.setStatus(OrderStatus.CONFIRMED);
        sampleView.setTotalAmount(new BigDecimal("698.00"));
        sampleView.setItemCount(2);
        sampleView.setItemsJson(objectMapper.writeValueAsString(List.of(
                new OrderItemResponse("item-101", "Hyderabadi Chicken Dum Biryani", 2, new BigDecimal("349.00"))
        )));
        sampleView.setCreatedAt(Instant.now());
        sampleView.setUpdatedAt(Instant.now());
    }

    @Test
    @DisplayName("getOrdersForCustomer -> Returns deserialized order summaries from order_summary_view")
    void getOrdersForCustomer_Success() {
        when(viewRepository.findByCustomerIdOrderByCreatedAtDesc(buyerId)).thenReturn(List.of(sampleView));

        List<OrderSummaryResponse> results = queryService.getOrdersForCustomer(buyerId.toString());

        assertEquals(1, results.size());
        assertEquals(orderId, results.get(0).orderId());
        assertEquals(new BigDecimal("698.00"), results.get(0).totalAmount());
        assertEquals(1, results.get(0).items().size());
        assertEquals("Hyderabadi Chicken Dum Biryani", results.get(0).items().get(0).itemName());
    }

    @Test
    @DisplayName("getOrdersForCustomer -> Rejects malformed customer UUID with ORDER_002")
    void getOrdersForCustomer_InvalidUuid_ThrowsOrder002() {
        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> queryService.getOrdersForCustomer("bad-uuid"));

        assertEquals(OrderConstants.ERROR_INVALID_ORDER_REQUEST, ex.getErrorCode());
        verifyNoInteractions(viewRepository);
    }

    @Test
    @DisplayName("getOrderById -> Returns order summary for owning customer")
    void getOrderById_OwnerSuccess() {
        when(viewRepository.findById(orderId)).thenReturn(Optional.of(sampleView));

        OrderSummaryResponse response = queryService.getOrderById(orderId.toString(), buyerId.toString(), false);

        assertEquals(orderId, response.orderId());
        assertEquals(OrderStatus.CONFIRMED, response.status());
    }

    @Test
    @DisplayName("getOrderById -> Blocks different non-admin customer with AccessDeniedException")
    void getOrderById_CrossCustomerBlocked() {
        when(viewRepository.findById(orderId)).thenReturn(Optional.of(sampleView));

        assertThrows(AccessDeniedException.class,
                () -> queryService.getOrderById(orderId.toString(), UUID.randomUUID().toString(), false));
    }

    @Test
    @DisplayName("getOrderById -> Allows ADMIN to inspect another customer's order")
    void getOrderById_AdminOverrideSuccess() {
        when(viewRepository.findById(orderId)).thenReturn(Optional.of(sampleView));

        OrderSummaryResponse response =
                queryService.getOrderById(orderId.toString(), UUID.randomUUID().toString(), true);

        assertEquals(orderId, response.orderId());
        assertEquals(buyerId, response.customerId());
    }

    @Test
    @DisplayName("getOrderById -> Rejects malformed UUID (ORDER_002) and unknown order (ORDER_001)")
    void getOrderById_InvalidAndNotFoundGuards() {
        assertEquals(OrderConstants.ERROR_INVALID_ORDER_REQUEST,
                assertThrows(BiteSyncException.class,
                        () -> queryService.getOrderById("not-a-uuid", buyerId.toString(), false)).getErrorCode());

        when(viewRepository.findById(orderId)).thenReturn(Optional.empty());
        assertEquals(OrderConstants.ERROR_ORDER_NOT_FOUND,
                assertThrows(BiteSyncException.class,
                        () -> queryService.getOrderById(orderId.toString(), buyerId.toString(), false)).getErrorCode());
    }
}