package com.stocker.notifications.api.rest;

import com.stocker.notifications.application.NotificationNotFoundException;
import com.stocker.notifications.application.NotificationService;
import com.stocker.notifications.domain.ExpirationNotification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService service;

    private final UUID userId = UUID.randomUUID();

    private ExpirationNotification notification() {
        return ExpirationNotification.create(userId, UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 10, 21), NOW);
    }

    @Test
    void listReturnsTheCallersNotifications() throws Exception {
        ExpirationNotification row = notification();
        when(service.list(userId)).thenReturn(List.of(row));

        mockMvc.perform(get("/notifications").header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(row.getId().toString()))
                .andExpect(jsonPath("$[0].pantryItemId").value(row.getPantryItemId().toString()))
                .andExpect(jsonPath("$[0].expirationDate").value("2026-10-21"))
                .andExpect(jsonPath("$[0].status").value("NEW"))
                .andExpect(jsonPath("$[0].acknowledgedAt").doesNotExist());
    }

    @Test
    void listExposesNameCategoryAndUnitAndToleratesNullProductId() throws Exception {
        ExpirationNotification row = ExpirationNotification.create(userId, UUID.randomUUID(), null,
                LocalDate.of(2026, 10, 21), NOW, "Nana's jam", null, "jar");
        when(service.list(userId)).thenReturn(List.of(row));

        mockMvc.perform(get("/notifications").header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemName").value("Nana's jam"))
                .andExpect(jsonPath("$[0].unit").value("jar"))
                .andExpect(jsonPath("$[0].productId").doesNotExist());
    }

    @Test
    void missingUserHeaderIsUnauthorised() throws Exception {
        mockMvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedUserHeaderIsUnauthorised() throws Exception {
        mockMvc.perform(get("/notifications").header("X-User-Id", "not-a-uuid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acknowledgeReturnsTheAcknowledgedNotification() throws Exception {
        ExpirationNotification row = notification();
        row.acknowledge(NOW.plusSeconds(5));
        when(service.acknowledge(userId, row.getId())).thenReturn(row);

        mockMvc.perform(post("/notifications/{id}/acknowledge", row.getId())
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedAt").value("2026-10-19T01:00:05Z"));
    }

    @Test
    void acknowledgingAnUnknownNotificationIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.acknowledge(userId, id)).thenThrow(new NotificationNotFoundException(id));

        mockMvc.perform(post("/notifications/{id}/acknowledge", id).header("X-User-Id", userId.toString()))
                .andExpect(status().isNotFound());
    }
}
