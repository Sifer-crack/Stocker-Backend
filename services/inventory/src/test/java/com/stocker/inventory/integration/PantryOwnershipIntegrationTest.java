package com.stocker.inventory.integration;

import com.stocker.inventory.api.rest.ApiExceptionHandler;
import com.stocker.inventory.api.rest.InternalPantryItemController;
import com.stocker.inventory.api.rest.PantryItemController;
import com.stocker.inventory.application.InMemoryPantryItemRepository;
import com.stocker.inventory.application.PantryItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The pantry belongs to the user in the gateway-verified X-User-Id header; nobody else can touch it. */
@Tag("feature-integration")
class PantryOwnershipIntegrationTest {

    private MockMvc mockMvc;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private String itemOfA;

    @BeforeEach
    void setUp() throws Exception {
        PantryItemService service = new PantryItemService(new InMemoryPantryItemRepository());
        mockMvc = MockMvcBuilders.standaloneSetup(new PantryItemController(service), new InternalPantryItemController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String body = mockMvc.perform(post("/pantry-items").header("X-User-Id", a.toString())
                        .param("name", "Jam").param("quantity", "2").param("expirationDate", "2026-10-25"))
                .andReturn().getResponse().getContentAsString();
        itemOfA = body.replaceAll(".*\"pantry_item_id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void ownerSeesAndChangesTheirItem() throws Exception {
        mockMvc.perform(get("/pantry-items").header("X-User-Id", a.toString()))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(put("/pantry-items/{id}", itemOfA).header("X-User-Id", a.toString()).param("quantity", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(5));
    }

    @Test
    void aQueryParamUserIdCannotImpersonateAnotherUser() throws Exception {
        mockMvc.perform(get("/pantry-items").header("X-User-Id", b.toString()).param("userId", a.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void otherUserSeesNothing() throws Exception {
        mockMvc.perform(get("/pantry-items").header("X-User-Id", b.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void otherUserCannotUpdateQuantityExpiryOrDelete() throws Exception {
        mockMvc.perform(put("/pantry-items/{id}", itemOfA).header("X-User-Id", b.toString()).param("quantity", "9"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());
        mockMvc.perform(put("/pantry-items/{id}/expiry", itemOfA).header("X-User-Id", b.toString())
                        .param("expirationDate", "2030-01-01"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/pantry-items/{id}", itemOfA).header("X-User-Id", b.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").exists());

        // untouched
        mockMvc.perform(get("/pantry-items").header("X-User-Id", a.toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[0].expirationDate").value("2026-10-25"));
    }

    @Test
    void postingWithAnotherUsersIdInTheBodyCreatesTheItemForTheCaller() throws Exception {
        mockMvc.perform(post("/pantry-items").header("X-User-Id", b.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + a + "\",\"name\":\"Planted\",\"quantity\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user_id").value(b.toString()));
        mockMvc.perform(get("/pantry-items").header("X-User-Id", a.toString()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void missingOrInvalidHeaderIs401WithAJsonMessage() throws Exception {
        mockMvc.perform(get("/pantry-items")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
        mockMvc.perform(get("/pantry-items").header("X-User-Id", "not-a-uuid"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/pantry-items").param("name", "x").param("quantity", "1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/pantry-items/{id}", itemOfA).param("quantity", "3"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/pantry-items/{id}/expiry", itemOfA)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/pantry-items/{id}", itemOfA)).andExpect(status().isUnauthorized());
    }

    @Test
    void deletingAMissingItemIs404AndDeletingYourOwnWorks() throws Exception {
        mockMvc.perform(delete("/pantry-items/{id}", UUID.randomUUID()).header("X-User-Id", a.toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/pantry-items/{id}", itemOfA).header("X-User-Id", a.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/pantry-items").header("X-User-Id", a.toString()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theInternalExpiringEndpointStaysHeaderless() throws Exception {
        mockMvc.perform(get("/internal/pantry-items/expiring").param("asOf", "2026-10-24"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void clientErrorsOnAddAreBadRequestsNotServerErrors() throws Exception {
        mockMvc.perform(post("/pantry-items").header("X-User-Id", a.toString())
                        .param("name", "x".repeat(256)).param("quantity", "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        mockMvc.perform(post("/pantry-items").header("X-User-Id", a.toString())
                        .param("name", "x").param("category", "c".repeat(101)).param("quantity", "1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/pantry-items").header("X-User-Id", a.toString())
                        .param("name", "x").param("unit", "u".repeat(51)).param("quantity", "1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/pantry-items").header("X-User-Id", a.toString())
                        .param("name", "x").param("quantity", "100001"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/pantry-items/{id}", itemOfA).header("X-User-Id", a.toString()).param("quantity", "100001"))
                .andExpect(status().isBadRequest());
    }
}
