package com.stocker.inventory.api.rest;

import com.stocker.inventory.application.PantryItemNotFoundException;
import com.stocker.inventory.application.PantryItemService;
import com.stocker.inventory.domain.PantryItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PantryItemController.class)
class PantryItemExpiryControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PantryItemService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    @Test
    void addPassesTheExpirationDateAndThresholdToTheService() throws Exception {
        when(service.addPantryItem(userId, productId, 2, TODAY.plusDays(7), 3)).thenReturn(
                new PantryItem(itemId, userId, productId, 2, TODAY.plusDays(7), 3));

        mockMvc.perform(post("/pantry-items")
                        .header("X-User-Id", userId.toString()).param("productId", productId.toString())
                        .param("quantity", "2").param("expirationDate", "2026-10-26").param("notifyDaysBefore", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expirationDate").value("2026-10-26"))
                .andExpect(jsonPath("$.notifyDaysBefore").value(3));

        verify(service).addPantryItem(userId, productId, 2, TODAY.plusDays(7), 3);
    }

    @Test
    void addWithoutExpiryPassesNulls() throws Exception {
        when(service.addPantryItem(userId, productId, 1, null, null)).thenReturn(
                new PantryItem(itemId, userId, productId, 1));

        mockMvc.perform(post("/pantry-items")
                        .header("X-User-Id", userId.toString()).param("productId", productId.toString())
                        .param("quantity", "1"))
                .andExpect(status().isOk());

        verify(service).addPantryItem(userId, productId, 1, null, null);
    }

    @Test
    void invalidInputIsABadRequestWithTheMessage() throws Exception {
        when(service.addPantryItem(any(), any(), eq(1), isNull(), eq(3)))
                .thenThrow(new IllegalArgumentException("A notification threshold needs an expiration date"));

        mockMvc.perform(post("/pantry-items")
                        .header("X-User-Id", userId.toString()).param("productId", productId.toString())
                        .param("quantity", "1").param("notifyDaysBefore", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A notification threshold needs an expiration date"));
    }

    @Test
    void updateExpiryReturnsTheUpdatedItem() throws Exception {
        when(service.updateExpiry(userId, itemId, TODAY.plusDays(2), 1)).thenReturn(
                new PantryItem(itemId, userId, productId, 1, TODAY.plusDays(2), 1));

        mockMvc.perform(put("/pantry-items/{id}/expiry", itemId).header("X-User-Id", userId.toString())
                        .param("expirationDate", "2026-10-21").param("notifyDaysBefore", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expirationDate").value("2026-10-21"))
                .andExpect(jsonPath("$.notifyDaysBefore").value(1));
    }

    @Test
    void updateExpiryOfAnUnknownItemIsNotFound() throws Exception {
        when(service.updateExpiry(eq(userId), eq(itemId), any(), any()))
                .thenThrow(new PantryItemNotFoundException());

        mockMvc.perform(put("/pantry-items/{id}/expiry", itemId).header("X-User-Id", userId.toString())
                        .param("expirationDate", "2026-10-21"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Pantry item not found"));
    }

    @Test
    void missingHeaderIs401WithAJsonMessageAndNeverReachesTheService() throws Exception {
        mockMvc.perform(get("/pantry-items").param("userId", userId.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
        mockMvc.perform(get("/pantry-items").header("X-User-Id", "garbage"))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void deleteOfAMissingItemIsNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new PantryItemNotFoundException()).when(service).deletePantryItem(userId, itemId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/pantry-items/{id}", itemId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Pantry item not found"));
    }

    @Test
    void anUnresolvedUniquenessRaceIsAConflictNotA500() throws Exception {
        when(service.addPantryItem(any(com.stocker.inventory.application.AddPantryItemCommand.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));
        mockMvc.perform(post("/pantry-items").header("X-User-Id", userId.toString())
                        .param("productId", productId.toString()).param("name", "Milk").param("quantity", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }
}
