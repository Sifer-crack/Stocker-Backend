package com.stocker.catalog.application;

import java.util.HashMap;
import java.util.Map;


public class ProductNameNormalizer {
    private ProductNameNormalizer() {}

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        // onion
        ALIASES.put("red onions", "red onion");
        ALIASES.put("red onions not bagged", "red onion");

        ALIASES.put("white onions", "white onion");
        ALIASES.put("white onions not bagged", "white onion");

        // tomato
        ALIASES.put("tomatoes", "tomato");
        ALIASES.put("fresh tomatoes", "tomato");
        ALIASES.put("loose tomatoes", "tomato");
        ALIASES.put("nz tomato", "tomato");

        // apple
        ALIASES.put("rose apples", "rose apple");

        // garlic
        ALIASES.put("garlic nz", "nz garlic");

        // banana
        ALIASES.put("bananas", "banana");
        ALIASES.put("fresh banana", "banana");
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }

        String normalized = name.toLowerCase().trim().replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ");

        return ALIASES.getOrDefault(normalized, normalized);
    }
}


