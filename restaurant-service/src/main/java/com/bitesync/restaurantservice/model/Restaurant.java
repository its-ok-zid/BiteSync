package com.bitesync.restaurantservice.model;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "restaurants")
@CompoundIndexes({
        @CompoundIndex(
                name = "idx_city_status_rating_id",
                def = "{'cityKey': 1, 'status': 1, 'rating': -1, '_id': -1}"
        ),
        @CompoundIndex(
                name = "idx_status_rating_id",
                def = "{'status': 1, 'rating': -1, '_id': -1}"
        ),
        @CompoundIndex(
                name = "idx_owner_status",
                def = "{'ownerId': 1, 'status': 1}"
        )
})
public class Restaurant {

    @Id
    private String id;

    private String ownerId;
    private String name;
    private String city;
    private String cityKey; // Lowercase normalized city for index-backed exact lookups
    private List<String> cuisineTypes = new ArrayList<>();
    private RestaurantStatus status;
    private double rating;
    private List<MenuCategory> menuCategories = new ArrayList<>();

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public Restaurant() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCity() { return city; }
    public void setCity(String city) {
        this.city = (city != null) ? city.trim() : null;
        this.cityKey = (this.city != null) ? this.city.toLowerCase() : null;
    }

    public String getCityKey() { return cityKey; }
    public void setCityKey(String cityKey) { this.cityKey = cityKey; }

    public List<String> getCuisineTypes() { return cuisineTypes; }
    public void setCuisineTypes(List<String> cuisineTypes) {
        this.cuisineTypes = (cuisineTypes != null) ? cuisineTypes : new ArrayList<>();
    }

    public RestaurantStatus getStatus() { return status; }
    public void setStatus(RestaurantStatus status) { this.status = status; }

    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }

    public List<MenuCategory> getMenuCategories() { return menuCategories; }
    public void setMenuCategories(List<MenuCategory> menuCategories) {
        this.menuCategories = (menuCategories != null) ? menuCategories : new ArrayList<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}