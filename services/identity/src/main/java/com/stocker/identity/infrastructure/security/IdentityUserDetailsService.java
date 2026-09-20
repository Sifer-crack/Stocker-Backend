package com.stocker.identity.infrastructure.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.stocker.identity.infrastructure.persistence.UserRepository;

@Service
public class IdentityUserDetailsService implements UserDetailsService {

	private final UserRepository userRepository;

	public IdentityUserDetailsService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		return userRepository.findByEmail(email)
			.map(UserPrincipal::new)
			.orElseThrow(() -> new UsernameNotFoundException("User not found"));
	}
}
