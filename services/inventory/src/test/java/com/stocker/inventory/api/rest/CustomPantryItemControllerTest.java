package com.stocker.inventory.api.rest;

import com.stocker.inventory.application.AddPantryItemCommand;
import com.stocker.inventory.application.PantryItemService;
import com.stocker.inventory.domain.PantryItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PantryItemController.class, InternalPantryItemController.class})
class CustomPantryItemControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean PantryItemService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void jsonBodyCreatesAFreeTextItem() throws Exception {
        when(service.addPantryItem(any(AddPantryItemCommand.class))).thenReturn(new PantryItem(UUID.randomUUID(),
                userId, null, "Nana's jam", "preserves", "jar", 2, LocalDate.of(2026, 11, 1), 5));

        mockMvc.perform(post("/pantry-items").header("X-User-Id", userId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"userId":"%s","name":"Nana's jam","category":"preserves","unit":"jar","quantity":2,
                 "expirationDate":"2026-11-01","notifyDaysBefore":5}""".formatted(UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Nana's jam"))
            .andExpect(jsonPath("$.unit").value("jar"))
            .andExpect(jsonPath("$.product_id").value(org.hamcrest.Matchers.nullValue()));

        ArgumentCaptor<AddPantryItemCommand> c = ArgumentCaptor.forClass(AddPantryItemCommand.class);
        verify(service).addPantryItem(c.capture());
        assertThat(c.getValue().productId()).isNull();
        // the userId field in the body is ignored: the verified header wins
        assertThat(c.getValue().userId()).isEqualTo(userId);
        assertThat(c.getValue().notifyDaysBefore()).isEqualTo(5);
    }

    @Test
    void queryParamsWithNameAndNoProductIdAlsoWork() throws Exception {
        when(service.addPantryItem(any(AddPantryItemCommand.class))).thenReturn(
                new PantryItem(UUID.randomUUID(), userId, null, "Honey", null, null, 1, null, null));
        mockMvc.perform(post("/pantry-items").header("X-User-Id", userId.toString()).param("name", "Honey")
                        .param("quantity", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Honey"));
    }

    @Test
    void missingProductAndNameIsABadRequest() throws Exception {
        when(service.addPantryItem(any(AddPantryItemCommand.class)))
                .thenThrow(new IllegalArgumentException("Either productId or name is required"));
        mockMvc.perform(post("/pantry-items").header("X-User-Id", userId.toString()).param("quantity", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Either productId or name is required"));
    }

    @Test
    void expiringResponseCarriesTheNameOfCustomItems() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 20);
        when(service.findDueForNotification(today)).thenReturn(List.of(new PantryItem(UUID.randomUUID(), userId,
                null, "Nana's jam", null, "jar", 1, today.plusDays(1), 3)));
        mockMvc.perform(get("/internal/pantry-items/expiring").param("asOf", "2026-10-20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Nana's jam"))
            .andExpect(jsonPath("$[0].unit").value("jar"))
            .andExpect(jsonPath("$[0].userId").value(userId.toString()));
    }
}
