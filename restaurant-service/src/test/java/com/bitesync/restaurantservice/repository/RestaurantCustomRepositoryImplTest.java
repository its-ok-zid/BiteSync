package com.bitesync.restaurantservice.repository;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestaurantCustomRepositoryImplTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private RestaurantCustomRepositoryImpl customRepository;

    @Test
    @DisplayName("Should build ESR query with normalized cityKey, status, rating/id DESC sort, and limit+1")
    void findByCursor_WithCityNoCursor_BuildsExpectedQuery() {
        when(mongoTemplate.find(any(Query.class), eq(Restaurant.class))).thenReturn(List.of());

        customRepository.findByCursor("  Bengaluru ", RestaurantStatus.ACTIVE, null, null, 10);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, times(1)).find(queryCaptor.capture(), eq(Restaurant.class));

        Query query = queryCaptor.getValue();
        List<Document> andConditions = query.getQueryObject().getList("$and", Document.class);
        assertNotNull(andConditions);
        assertEquals(2, andConditions.size());
        assertEquals("bengaluru", andConditions.get(0).getString("cityKey"));
        assertEquals(RestaurantStatus.ACTIVE, andConditions.get(1).get("status"));

        Document sort = query.getSortObject();
        assertEquals(-1, sort.get("rating"));
        assertEquals(-1, sort.get("_id"));
        assertEquals(11, query.getLimit()); // limit + 1 trick
    }

    @Test
    @DisplayName("Should build composite $or cursor filter without cityKey when city is null")
    void findByCursor_WithCompositeCursorNoCity_IncludesOrCriteria() {
        String cursorId = "65f1a29b4c8e12001a9c4b11";
        when(mongoTemplate.find(any(Query.class), eq(Restaurant.class))).thenReturn(List.of());

        customRepository.findByCursor(null, RestaurantStatus.ACTIVE, 4.7, cursorId, 5);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(Restaurant.class));

        Query query = queryCaptor.getValue();
        List<Document> andConditions = query.getQueryObject().getList("$and", Document.class);
        assertNotNull(andConditions);
        assertEquals(2, andConditions.size());
        assertFalse(andConditions.get(0).containsKey("cityKey"));
        assertEquals(RestaurantStatus.ACTIVE, andConditions.get(0).get("status"));

        // Verify composite cursor: (rating < 4.7) OR (rating == 4.7 AND _id < ObjectId(cursorId))
        List<Document> orConditions = andConditions.get(1).getList("$or", Document.class);
        assertNotNull(orConditions);
        assertEquals(2, orConditions.size());

        Document lowerRatingDoc = orConditions.get(0).get("rating", Document.class);
        assertEquals(4.7, lowerRatingDoc.get("$lt"));

        List<Document> tieBreakerAnd = orConditions.get(1).getList("$and", Document.class);
        assertEquals(4.7, tieBreakerAnd.get(0).get("rating"));
        Document lowerIdDoc = tieBreakerAnd.get(1).get("_id", Document.class);
        assertEquals(new ObjectId(cursorId), lowerIdDoc.get("$lt"));

        assertEquals(6, query.getLimit()); // limit + 1 trick
    }

    @Test
    @DisplayName("Should throw REST_002 immediately when afterId is not a valid MongoDB ObjectId")
    void findByCursor_InvalidCursorId_ThrowsRest002() {
        BiteSyncException ex = assertThrows(BiteSyncException.class, () ->
                customRepository.findByCursor("bengaluru", RestaurantStatus.ACTIVE, 4.5, "bad-id", 10)
        );

        assertEquals("REST_002", ex.getErrorCode());
        verifyNoInteractions(mongoTemplate);
    }
}