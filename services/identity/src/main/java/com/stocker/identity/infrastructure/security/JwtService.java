package com.stocker.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Service
public class JwtService {

	private final JwtEncoder encoder;
	private final JwtDecoder decoder;
	private final long accessTtlSeconds;
	private final long refreshTtlSeconds;

	public JwtService(
		@Value("${stocker.jwt.secret}") String secret,
		@Value("${stocker.jwt.access-ttl-seconds:900}") long accessTtlSeconds,
		@Value("${stocker.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < 32) {
			throw new IllegalStateException("stocker.jwt.secret must be at least 32 bytes for HS256");
		}
		SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		this.accessTtlSeconds = accessTtlSeconds;
		this.refreshTtlSeconds = refreshTtlSeconds;
	}

	public String issueAccessToken(String userId, String username) {
		return issue(userId, username, "access", accessTtlSeconds);
	}

	public String issueRefreshToken(String userId, String username) {
		return issue(userId, username, "refresh", refreshTtlSeconds);
	}

	public long accessTtlSeconds() {
		return accessTtlSeconds;
	}

	public Jwt decode(String token) {
		return decoder.decode(token);
	}

	public ValidatedRefresh validateRefreshToken(String token) {
		Jwt jwt;
		try {
			jwt = decoder.decode(token);
		} catch (JwtException ex) {
			throw new InvalidRefreshTokenException();
		}
		if (!"refresh".equals(jwt.getClaimAsString("type"))) {
			throw new InvalidRefreshTokenException();
		}
		return new ValidatedRefresh(jwt.getSubject(), jwt.getClaimAsString("username"));
	}

	private String issue(String userId, String username, String type, long ttlSeconds) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("stocker-identity")
			.subject(userId)
			.issuedAt(now)
			.expiresAt(now.plusSeconds(ttlSeconds))
			.claim("username", username)
			.claim("type", type)
			.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	public record ValidatedRefresh(String userId, String username) {
	}

	public static class InvalidRefreshTokenException extends RuntimeException {
	}
}
