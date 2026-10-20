package com.stocker.shopping.api.rest;

import com.stocker.shopping.application.FavoriteStoreNotFoundException;
import com.stocker.shopping.application.FavoriteStoreService;
import com.stocker.shopping.domain.FavoriteStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FavoriteStoreController.class)
class FavoriteStoreControllerTest {

	private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 19, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FavoriteStoreService service;

	private FavoriteStore store(String chain, String storeId) {
		return FavoriteStore.create("user-1", chain, storeId, NOW);
	}

	@Test
	void savingAStoreReturns201WithTheFavorite() throws Exception {
		FavoriteStore saved = store("paknsave", "ps:42");
		when(service.add("user-1", "paknsave", "ps:42")).thenReturn(saved);

		mockMvc.perform(post("/shopping/favorite-stores").header("X-User-Id", "user-1")
						.contentType(MediaType.APPLICATION_JSON).content("{\"chainId\":\"paknsave\",\"storeId\":\"ps:42\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(saved.getId().toString()))
				.andExpect(jsonPath("$.chainId").value("paknsave"))
				.andExpect(jsonPath("$.storeId").value("ps:42"))
				.andExpect(jsonPath("$.createdAt").value("2026-10-19T12:00:00Z"));
	}

	@Test
	void storeIdIsOptional() throws Exception {
		when(service.add("user-1", "newworld", null)).thenReturn(store("newworld", null));

		mockMvc.perform(post("/shopping/favorite-stores").header("X-User-Id", "user-1")
						.contentType(MediaType.APPLICATION_JSON).content("{\"chainId\":\"newworld\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.storeId").doesNotExist());
	}

	@Test
	void anUnknownChainIsABadRequest() throws Exception {
		when(service.add("user-1", "countdown", null)).thenThrow(new IllegalArgumentException("Unknown store chain 'countdown'"));

		mockMvc.perform(post("/shopping/favorite-stores").header("X-User-Id", "user-1")
						.contentType(MediaType.APPLICATION_JSON).content("{\"chainId\":\"countdown\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Unknown store chain 'countdown'"));
	}

	@Test
	void aMissingChainIsABadRequest() throws Exception {
		mockMvc.perform(post("/shopping/favorite-stores").header("X-User-Id", "user-1")
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void listReturnsTheCallersFavorites() throws Exception {
		when(service.list("user-1")).thenReturn(List.of(store("newworld", null), store("paknsave", "ps:1")));

		mockMvc.perform(get("/shopping/favorite-stores").header("X-User-Id", "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].chainId").value("newworld"))
				.andExpect(jsonPath("$[1].storeId").value("ps:1"));
	}

	@Test
	void removeReturns204() throws Exception {
		UUID id = UUID.randomUUID();

		mockMvc.perform(delete("/shopping/favorite-stores/{id}", id).header("X-User-Id", "user-1"))
				.andExpect(status().isNoContent());

		verify(service).remove("user-1", id);
	}

	@Test
	void removingAnUnknownFavoriteIs404() throws Exception {
		UUID id = UUID.randomUUID();
		doThrow(new FavoriteStoreNotFoundException(id)).when(service).remove("user-1", id);

		mockMvc.perform(delete("/shopping/favorite-stores/{id}", id).header("X-User-Id", "user-1"))
				.andExpect(status().isNotFound());
	}

	@Test
	void withoutAUserHeaderEveryEndpointIsUnauthorised() throws Exception {
		mockMvc.perform(get("/shopping/favorite-stores")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/shopping/favorite-stores").contentType(MediaType.APPLICATION_JSON).content("{\"chainId\":\"newworld\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/shopping/favorite-stores/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
	}
}
