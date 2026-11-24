package com.bitesync.restaurantservice.repository;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.restaurantservice.constants.RestaurantConstants;
import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.List;

public class RestaurantCustomRepositoryImpl implements RestaurantCustomRepository {

    private final MongoTemplate mongoTemplate;

    public RestaurantCustomRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<Restaurant> findByCursor(
            String cityKey,
            RestaurantStatus status,
            Double afterRating,
            String afterId,
            int limit) {

        List<Criteria> filters = new ArrayList<>();

        // E (Equality) filters — matches the prefix of our ESR compound index
        if (cityKey != null && !cityKey.isBlank()) {
            filters.add(Criteria.where("cityKey").is(cityKey.trim().toLowerCase()));
        }
        filters.add(Criteria.where("status").is(status));

        // Composite Keyset / Cursor condition: (rating < afterRating) OR (rating == afterRating AND _id < afterId)
        if (afterRating != null && afterId != null && !afterId.isBlank()) {
            if (!ObjectId.isValid(afterId)) {
                throw new BiteSyncException(
                        RestaurantConstants.MSG_INVALID_CURSOR_ID,
                        RestaurantConstants.ERROR_INVALID_REQUEST
                );
            }
            ObjectId cursorObjectId = new ObjectId(afterId);

            Criteria lowerRating = Criteria.where("rating").lt(afterRating);
            Criteria sameRatingLowerId = new Criteria().andOperator(
                    Criteria.where("rating").is(afterRating),
                    Criteria.where("_id").lt(cursorObjectId)
            );
            filters.add(new Criteria().orOperator(lowerRating, sameRatingLowerId));
        }

        Query query = new Query();
        query.addCriteria(new Criteria().andOperator(filters.toArray(new Criteria[0])));

        // S (Sort) — aligns exactly with { cityKey: 1, status: 1, rating: -1, _id: -1 }
        query.with(Sort.by(
                Sort.Order.desc("rating"),
                Sort.Order.desc("_id")
        ));

        // Fetch 1 extra record to compute hasNext without an extra COUNT query
        query.limit(limit + 1);

        return mongoTemplate.find(query, Restaurant.class);
    }
}