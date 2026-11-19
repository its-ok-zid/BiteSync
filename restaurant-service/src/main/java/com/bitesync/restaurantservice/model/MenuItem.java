package com.bitesync.restaurantservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class MenuItem {

    private String itemId;
    private String name;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal price;

    @JsonProperty("isAvailable")
    private boolean available = true;

    private List<String> addons = new ArrayList<>();

    public MenuItem() {
    }

    public MenuItem(String itemId, String name, BigDecimal price, boolean available, List<String> addons) {
        this.itemId = itemId;
        this.name = name;
        this.price = price;
        this.available = available;
        this.addons = (addons != null) ? addons : new ArrayList<>();
    }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    @JsonProperty("isAvailable")
    public boolean isAvailable() { return available; }

    @JsonProperty("isAvailable")
    public void setAvailable(boolean available) { this.available = available; }

    public List<String> getAddons() { return addons; }
    public void setAddons(List<String> addons) {
        this.addons = (addons != null) ? addons : new ArrayList<>();
    }
}