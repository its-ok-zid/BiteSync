package com.bitesync.restaurantservice.model;

import java.util.ArrayList;
import java.util.List;

public class MenuCategory {

    private String name;
    private List<MenuItem> items = new ArrayList<>();

    public MenuCategory() {
    }

    public MenuCategory(String name, List<MenuItem> items) {
        this.name = name;
        this.items = (items != null) ? items : new ArrayList<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<MenuItem> getItems() { return items; }
    public void setItems(List<MenuItem> items) {
        this.items = (items != null) ? items : new ArrayList<>();
    }
}