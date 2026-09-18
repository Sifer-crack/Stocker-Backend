package com.stocker.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.stocker.identity.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);
}
