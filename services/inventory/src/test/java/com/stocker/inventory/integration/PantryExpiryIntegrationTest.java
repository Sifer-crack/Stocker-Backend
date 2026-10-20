package com.stocker.inventory.integration;

import com.stocker.inventory.api.rest.ApiExceptionHandler;
import com.stocker.inventory.api.rest.InternalPantryItemController;
import com.stocker.inventory.api.rest.PantryItemController;
import com.stocker.inventory.application.InMemoryPantryItemRepository;
import com.stocker.inventory.application.PantryItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Inventory half of the expiry feature: controller + service + repository port, HTTP in, HTTP out. */
@Tag("feature-integration")
class PantryExpiryIntegrationTest {

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PantryItemService service = new PantryItemService(new InMemoryPantryItemRepository());
        mockMvc = MockMvcBuilders.standaloneSetup(new PantryItemController(service), new InternalPantryItemController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void anItemAddedWithExpiryAppearsInTheExpiringListOnceItsWindowOpens() throws Exception {
        mockMvc.perform(post("/pantry-items").header("X-User-Id", userId.toString())
                        .param("productId", UUID.randomUUID().toString()).param("quantity", "1")
                        .param("expirationDate", "2026-10-25").param("notifyDaysBefore", "2"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/pantry-items/expiring").param("asOf", "2026-10-22"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/internal/pantry-items/expiring").param("asOf", "2026-10-23"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(userId.toString()));
    }

    @Test
    void changingTheThresholdThroughTheApiMovesTheWindow() throws Exception {
        String body = mockMvc.perform(post("/pantry-items").header("X-User-Id", userId.toString())
                        .param("productId", UUID.randomUUID().toString()).param("quantity", "1")
                        .param("expirationDate", "2026-10-25"))
                .andReturn().getResponse().getContentAsString();
        String itemId = body.replaceAll(".*\"pantry_item_id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/internal/pantry-items/expiring").param("asOf", "2026-10-19"))
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(put("/pantry-items/{id}/expiry", itemId)
                        .param("expirationDate", "2026-10-25").param("notifyDaysBefore", "10"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/pantry-items/expiring").param("asOf", "2026-10-19"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void invalidThresholdIsRejectedEndToEnd() throws Exception {
        mockMvc.perform(post("/pantry-items").header("X-User-Id", userId.toString())
                        .param("productId", UUID.randomUUID().toString()).param("quantity", "1")
                        .param("notifyDaysBefore", "3"))
                .andExpect(status().isBadRequest());
    }
}
