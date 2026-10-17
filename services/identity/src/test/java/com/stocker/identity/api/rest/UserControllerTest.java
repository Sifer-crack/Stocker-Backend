package com.stocker.identity.api.rest;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stocker.identity.api.rest.dto.UserResponse;
import com.stocker.identity.application.AuthService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class)
@org.springframework.context.annotation.Import({
	com.stocker.identity.infrastructure.security.SecurityConfig.class,
	org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration.class })
class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private com.stocker.identity.infrastructure.security.JwtService jwtService;

	@MockitoBean
	private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

	@MockitoBean
	private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

	@Test
	void getMeReturns200WithToken() throws Exception {
		UUID id = UUID.randomUUID();
		when(authService.getCurrentUser()).thenReturn(new UserResponse(id, "jdoe@example.com", "Jane", "Doe", 150.0));
		mockMvc.perform(get("/identity/me").with(jwt().jwt(builder -> builder.subject(id.toString()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("jdoe@example.com"))
			.andExpect(jsonPath("$.groceryBudget").value(150.0));
	}

	@Test
	void getMeReturns401WithoutToken() throws Exception {
		mockMvc.perform(get("/identity/me"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void patchMeUpdatesBudget() throws Exception {
		UUID id = UUID.randomUUID();
		when(authService.updateBudget(200.0))
			.thenReturn(new UserResponse(id, "jdoe@example.com", "Jane", "Doe", 200.0));
		mockMvc.perform(patch("/identity/me").with(jwt().jwt(builder -> builder.subject(id.toString())))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"groceryBudget":200.0}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.groceryBudget").value(200.0));
	}

	@Test
	void patchMeClearsBudgetOnNull() throws Exception {
		UUID id = UUID.randomUUID();
		when(authService.updateBudget(any()))
			.thenReturn(new UserResponse(id, "jdoe@example.com", "Jane", "Doe", null));
		mockMvc.perform(patch("/identity/me").with(jwt().jwt(builder -> builder.subject(id.toString())))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"groceryBudget":null}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.groceryBudget").doesNotExist());
	}

	@Test
	void patchMeReturns401WithoutToken() throws Exception {
		mockMvc.perform(patch("/identity/me")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"groceryBudget":200.0}"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void patchMeRejectsNegativeBudget() throws Exception {
		UUID id = UUID.randomUUID();
		mockMvc.perform(patch("/identity/me").with(jwt().jwt(builder -> builder.subject(id.toString())))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"groceryBudget":-5}"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void getSpendingReportReturns200WithToken() throws Exception {
		UUID id = UUID.randomUUID();
		when(authService.getSpendingReport()).thenReturn("""
			{"total":42.5,"items":["milk"]}""");
		mockMvc.perform(get("/identity/me/spending-report").with(jwt().jwt(builder -> builder.subject(id.toString()))))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.total").value(42.5))
			.andExpect(jsonPath("$.items[0]").value("milk"));
	}

	@Test
	void getSpendingReportReturns401WithoutToken() throws Exception {
		mockMvc.perform(get("/identity/me/spending-report"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void postSpendingReportUpdates() throws Exception {
		UUID id = UUID.randomUUID();
		when(authService.updateSpendingReport(any())).thenReturn("""
			{"total":42.5}""");
		mockMvc.perform(post("/identity/me/spending-report").with(jwt().jwt(builder -> builder.subject(id.toString())))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"total":42.5}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(42.5));
	}

	@Test
	void postSpendingReportClearsOnNull() throws Exception {
		UUID id = UUID.randomUUID();
		when(authService.updateSpendingReport(any())).thenReturn(null);
		mockMvc.perform(post("/identity/me/spending-report").with(jwt().jwt(builder -> builder.subject(id.toString())))
				.contentType(MediaType.APPLICATION_JSON)
				.content("null"))
			.andExpect(status().isOk());
	}

	@Test
	void postSpendingReportReturns401WithoutToken() throws Exception {
		mockMvc.perform(post("/identity/me/spending-report")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"total":42.5}"""))
			.andExpect(status().isUnauthorized());
	}
}
