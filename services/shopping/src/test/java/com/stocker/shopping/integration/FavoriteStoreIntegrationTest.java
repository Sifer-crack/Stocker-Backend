package com.stocker.shopping.integration;

import com.stocker.shopping.api.rest.ApiExceptionHandler;
import com.stocker.shopping.api.rest.FavoriteStoreController;
import com.stocker.shopping.api.rest.ShoppingItemController;
import com.stocker.shopping.application.FavoriteStoreService;
import com.stocker.shopping.application.InMemoryFavoriteStoreRepository;
import com.stocker.shopping.application.ShoppingItemService;
import com.stocker.shopping.domain.ShoppingListItem;
import com.stocker.shopping.infrastructure.persistence.ShoppingItemPriceRepository;
import com.stocker.shopping.infrastructure.persistence.ShoppingListItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The favorite-stores story through HTTP: save two stores, see them in the favorites section, pick one,
 * add an item from it to the shopping list (the real ShoppingItemService). Browsing the store's products is
 * pricing's GET /api/pricing/stores/{chain}/products and is covered in pricing and the gateway.
 */
@Tag("feature-integration")
class FavoriteStoreIntegrationTest {

	private MockMvc mockMvc;
	private final List<ShoppingListItem> savedItems = new ArrayList<>();

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
		ShoppingListItemRepository items = mock(ShoppingListItemRepository.class);
		when(items.save(any(ShoppingListItem.class))).thenAnswer(invocation -> {
			savedItems.add(invocation.getArgument(0));
			return invocation.getArgument(0);
		});
		when(items.findByUserIdOrderByCreatedAtDesc(anyString())).thenAnswer(invocation -> List.copyOf(savedItems));
		ShoppingItemPriceRepository prices = mock(ShoppingItemPriceRepository.class);
		when(prices.findByItemIdIn(anyList())).thenReturn(List.of());
		ShoppingItemService shopping = new ShoppingItemService(items, prices, mock(ApplicationEventPublisher.class), clock);
		FavoriteStoreService favorites = new FavoriteStoreService(new InMemoryFavoriteStoreRepository(), clock);
		mockMvc = MockMvcBuilders.standaloneSetup(new FavoriteStoreController(favorites), new ShoppingItemController(shopping))
				.setControllerAdvice(new ApiExceptionHandler()).build();
	}

	@Test
	void saveStoresPickOneAndAddItsItemToTheShoppingList() throws Exception {
		for (String chain : List.of("paknsave", "woolworths")) {
			mockMvc.perform(post("/shopping/favorite-stores").header("X-User-Id", "user-1")
							.contentType(MediaType.APPLICATION_JSON).content("{\"chainId\":\"" + chain + "\"}"))
					.andExpect(status().isCreated());
		}
		mockMvc.perform(get("/shopping/favorite-stores").header("X-User-Id", "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].chainId").value("paknsave"))
				.andExpect(jsonPath("$[1].chainId").value("woolworths"));

		// the user picked PAK'nSave, saw "Anchor Blue Top Milk 2L" in its product list and adds it
		mockMvc.perform(post("/shopping/items").header("X-User-Id", "user-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Anchor Blue Top Milk 2L\",\"category\":\"milk\",\"quantity\":1}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Anchor Blue Top Milk 2L"));
		mockMvc.perform(get("/shopping/items").header("X-User-Id", "user-1"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Anchor Blue Top Milk 2L"));
	}

	@Test
	void favoritesArePerUserAndRejectUnknownChains() throws Exception {
		mockMvc.perform(post("/shopping/favorite-stores").header("X-User-Id", "user-1")
						.contentType(MediaType.APPLICATION_JSON).content("{\"chainId\":\"newworld\"}"))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/shopping/favorite-stores").header("X-User-Id", "user-2"))
				.andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(post("/shopping/favorite-stores").header("X-User-Id", "user-1")
						.contentType(MediaType.APPLICATION_JSON).content("{\"chainId\":\"countdown\"}"))
				.andExpect(status().isBadRequest());
	}
}
