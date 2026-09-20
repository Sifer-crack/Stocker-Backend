package com.stocker.identity.api.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthzController.class)
class HealthzControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

	@Test
	void healthzReturnsUp() throws Exception {
		mockMvc.perform(get("/healthz"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

}
