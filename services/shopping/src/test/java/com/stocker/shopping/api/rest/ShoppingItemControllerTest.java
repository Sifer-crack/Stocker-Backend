package com.stocker.shopping.api.rest;

import com.stocker.shopping.application.AddItemCommand;
import com.stocker.shopping.application.ShoppingItemService;
import com.stocker.shopping.application.model.ItemView;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShoppingItemController.class)
class ShoppingItemControllerTest {

	private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ShoppingItemService service;

	private ItemView pendingView(UUID id) {
		return new ItemView(id, "full cream milk 2L", null, "milk", null, 2, "pending", null, List.of(), null, NOW);
	}

	@Test
	void addingAnItemReturns201ImmediatelyAsPendingWithNoComparisonYet() throws Exception {
		UUID id = UUID.randomUUID();
		when(service.addItem(any(AddItemCommand.class))).thenReturn(pendingView(id));

		mockMvc.perform(post("/shopping/items")
						.header("X-User-Id", "user-1")
						.header("X-Household-Id", "house-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"  full cream milk 2L \",\"category\":\"milk\",\"region\":\"\",\"quantity\":2}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.comparisonStatus").value("pending"))
				.andExpect(jsonPath("$.cheapest").doesNotExist())
				.andExpect(jsonPath("$.prices.length()").value(0));

		ArgumentCaptor<AddItemCommand> command = ArgumentCaptor.forClass(AddItemCommand.class);
		verify(service).addItem(command.capture());
		assertEquals("user-1", command.getValue().userId());
		assertEquals("house-1", command.getValue().householdId());
		assertEquals("full cream milk 2L", command.getValue().name());
		assertNull(command.getValue().region());
		assertEquals(2, command.getValue().quantity());
	}

	@Test
	void quantityDefaultsToOne() throws Exception {
		when(service.addItem(any(AddItemCommand.class))).thenReturn(pendingView(UUID.randomUUID()));

		mockMvc.perform(post("/shopping/items").header("X-User-Id", "user-1")
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"milk\"}")).andExpect(status().isCreated());

		ArgumentCaptor<AddItemCommand> command = ArgumentCaptor.forClass(AddItemCommand.class);
		verify(service).addItem(command.capture());
		assertEquals(1, command.getValue().quantity());
	}

	@Test
	void aBlankNameIsRejectedWithTheRepoErrorShape() throws Exception {
		mockMvc.perform(post("/shopping/items").header("X-User-Id", "user-1")
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("name")));

		verify(service, never()).addItem(any());
	}

	@Test
	void aMissingUserHeaderIsUnauthorized() throws Exception {
		mockMvc.perform(post("/shopping/items").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"milk\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").exists());

		verify(service, never()).addItem(any());
	}

	@Test
	void listReturnsTheCallersItems() throws Exception {
		when(service.listForUser("user-1")).thenReturn(List.of(pendingView(UUID.randomUUID())));

		mockMvc.perform(get("/shopping/items").header("X-User-Id", "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].comparisonStatus").value("pending"));
	}

	@Test
	void anotherUsersOrUnknownItemIsA404() throws Exception {
		UUID id = UUID.randomUUID();
		when(service.findForUser(id, "user-1")).thenReturn(Optional.empty());

		mockMvc.perform(get("/shopping/items/" + id).header("X-User-Id", "user-1"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("item not found"));
	}
}
