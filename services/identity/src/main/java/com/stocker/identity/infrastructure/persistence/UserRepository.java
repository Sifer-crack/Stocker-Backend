package com.stocker.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.stocker.identity.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	@Modifying
	@Query(value = "UPDATE users SET spending_report = CAST(:report AS jsonb), updated_at = now() WHERE user_id = :userId",
		nativeQuery = true)
	int updateSpendingReport(@Param("userId") UUID userId, @Param("report") String report);
}
