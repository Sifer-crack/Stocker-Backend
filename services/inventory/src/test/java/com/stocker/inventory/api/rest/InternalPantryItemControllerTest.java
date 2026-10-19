package com.stocker.inventory.api.rest;

import com.stocker.inventory.application.PantryItemService;
import com.stocker.inventory.domain.PantryItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalPantryItemController.class)
class InternalPantryItemControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PantryItemService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    /** The notifications service parses exactly these field names (see its InventoryPantryClientTest). */
    @Test
    void expiringReturnsTheContractTheNotificationsServiceReads() throws Exception {
        when(service.findDueForNotification(TODAY)).thenReturn(List.of(
                new PantryItem(itemId, userId, productId, 2, TODAY.plusDays(1), 3)));

        mockMvc.perform(get("/internal/pantry-items/expiring").param("asOf", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pantryItemId").value(itemId.toString()))
                .andExpect(jsonPath("$[0].userId").value(userId.toString()))
                .andExpect(jsonPath("$[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].expirationDate").value("2026-10-20"))
                .andExpect(jsonPath("$[0].notifyDaysBefore").value(3));
    }

    @Test
    void asOfIsRequired() throws Exception {
        mockMvc.perform(get("/internal/pantry-items/expiring")).andExpect(status().isBadRequest());
    }
}
