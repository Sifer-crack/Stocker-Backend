package com.stocker.notifications.integration;

import com.stocker.notifications.api.rest.ApiExceptionHandler;
import com.stocker.notifications.api.rest.NotificationController;
import com.stocker.notifications.application.ExpirationScanService;
import com.stocker.notifications.application.InMemoryNotificationRepository;
import com.stocker.notifications.application.MutableClock;
import com.stocker.notifications.application.NotificationService;
import com.stocker.notifications.infrastructure.client.InventoryPantryClient;
import com.stocker.notifications.infrastructure.client.InventoryStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notifications half of the expiry feature, whole path: stubbed inventory over HTTP (the real contract
 * shape) -> real HTTP client -> scan -> notification store -> REST list / acknowledge.
 */
@Tag("feature-integration")
class ExpiryNotificationIntegrationTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();

    private InventoryStub inventory;
    private MutableClock clock;
    private ExpirationScanService scan;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        // inventory's behaviour: the item (expires 2026-10-21, threshold 2) is listed from 2026-10-19 on
        inventory = new InventoryStub(query -> {
            LocalDate asOf = LocalDate.parse(query.substring(query.indexOf('=') + 1));
            return InventoryStub.Reply.ok(asOf.isBefore(LocalDate.of(2026, 10, 19)) ? "[]" : """
                    [{"pantryItemId":"%s","userId":"%s","productId":"%s","quantity":1,
                      "expirationDate":"2026-10-21","notifyDaysBefore":2}]
                    """.formatted(ITEM, USER, UUID.randomUUID()));
        });
        clock = new MutableClock(Instant.parse("2026-10-18T01:00:00Z"));
        InMemoryNotificationRepository repository = new InMemoryNotificationRepository();
        scan = new ExpirationScanService(new InventoryPantryClient(inventory.baseUrl()), repository, clock);
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(new NotificationService(repository, clock)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() {
        inventory.close();
    }

    @Test
    void expiringItemBecomesANotificationTheUserCanAcknowledge() throws Exception {
        scan.scan();                                           // 18th: inventory lists nothing yet
        mockMvc.perform(get("/notifications").header("X-User-Id", USER.toString()))
                .andExpect(jsonPath("$.length()").value(0));

        clock.advance(Duration.ofDays(1));
        scan.scan();                                           // 19th: window opened
        scan.scan();                                           // a second scan the same day: no duplicate

        String body = mockMvc.perform(get("/notifications").header("X-User-Id", USER.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].pantryItemId").value(ITEM.toString()))
                .andExpect(jsonPath("$[0].status").value("NEW"))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/notifications/{id}/acknowledge", id).header("X-User-Id", USER.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mockMvc.perform(get("/notifications").header("X-User-Id", USER.toString()))
                .andExpect(jsonPath("$[0].status").value("ACKNOWLEDGED"));
    }

    @Test
    void otherUsersSeeNothing() throws Exception {
        clock.advance(Duration.ofDays(1));
        scan.scan();

        mockMvc.perform(get("/notifications").header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
