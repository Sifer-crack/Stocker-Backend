package com.stocker.identity.api.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stocker.identity.api.rest.dto.AuthResponse;
import com.stocker.identity.api.rest.dto.UserResponse;
import com.stocker.identity.application.AuthService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

@WebMvcTest(controllers = AuthController.class)
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

	@Test
	void registerReturns201WithoutAuthHeader() throws Exception {
		when(authService.register(any())).thenReturn(new UserResponse(UUID.randomUUID(), "jdoe", "jdoe@example.com", "Jane", "Doe"));
		mockMvc.perform(post("/identity/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"username":"jdoe","email":"jdoe@example.com","password":"password123","firstName":"Jane","lastName":"Doe"}"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("jdoe@example.com"));
	}

	@Test
	void registerDuplicateEmailReturns409() throws Exception {
		when(authService.register(any())).thenThrow(new AuthService.EmailAlreadyExistsException());
		mockMvc.perform(post("/identity/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"username":"jdoe","email":"jdoe@example.com","password":"password123"}"""))
			.andExpect(status().isConflict());
	}

	@Test
	void registerValidationFailureReturns400() throws Exception {
		mockMvc.perform(post("/identity/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"username":"","email":"not-an-email","password":"short"}"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void loginReturnsTokens() throws Exception {
		when(authService.login(any())).thenReturn(new AuthResponse("access", "refresh", "Bearer", 900));
		mockMvc.perform(post("/identity/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"jdoe@example.com","password":"password123"}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("access"))
			.andExpect(jsonPath("$.refreshToken").value("refresh"));
	}

	@Test
	void loginBadCredentialsReturns401() throws Exception {
		when(authService.login(any())).thenThrow(new AuthService.BadCredentialsException());
		mockMvc.perform(post("/identity/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"jdoe@example.com","password":"wrong"}"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void refreshReturnsNewTokens() throws Exception {
		when(authService.refresh(any())).thenReturn(new AuthResponse("new-access", "new-refresh", "Bearer", 900));
		mockMvc.perform(post("/identity/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"refreshToken":"refresh"}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("new-access"));
	}

	@Test
	void refreshInvalidTokenReturns401() throws Exception {
		when(authService.refresh(any())).thenThrow(new AuthService.BadCredentialsException());
		mockMvc.perform(post("/identity/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"refreshToken":"bogus"}"""))
			.andExpect(status().isUnauthorized());
	}
}
