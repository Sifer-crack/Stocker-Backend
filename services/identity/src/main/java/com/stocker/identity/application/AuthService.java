package com.stocker.identity.application;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import com.stocker.identity.api.rest.dto.AuthResponse;
import com.stocker.identity.api.rest.dto.LoginRequest;
import com.stocker.identity.api.rest.dto.RegisterRequest;
import com.stocker.identity.api.rest.dto.UserResponse;
import com.stocker.identity.domain.User;
import com.stocker.identity.infrastructure.persistence.UserRepository;
import com.stocker.identity.infrastructure.security.JwtService;

@Service
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthenticationManager authenticationManager;
  private final JwtService jwtService;

  public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
      AuthenticationManager authenticationManager, JwtService jwtService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.authenticationManager = authenticationManager;
    this.jwtService = jwtService;
  }

  public UserResponse register(RegisterRequest request) {

    // check if user exists already by email
    if (userRepository.findByEmail(request.email()).isPresent()) {
      throw new EmailAlreadyExistsException();
    }

    // create user
    User user = new User();
    user.setEmail(request.email());
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setFirstName(request.firstName());
    user.setLastName(request.lastName());
    user.setGroceryBudget(request.groceryBudget());

    // save user
    User saved = userRepository.save(user);
    return toResponse(saved);
  }

  public Tokens login(LoginRequest request) {

    try {
      authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.email(), request.password()));
    } catch (org.springframework.security.core.AuthenticationException ex) {
      throw new BadCredentialsException(ex);
    }

    // Find user and return jswt tokens
    User user = userRepository.findByEmail(request.email()).orElseThrow(BadCredentialsException::new);
    return tokens(user);
  }

  public Tokens refresh(String refreshToken) {

    // ensure refresh token isnt empty
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new BadCredentialsException();
    }

    // check the user given refresh token is valid
    JwtService.ValidatedRefresh validated;
    try {
      validated = jwtService.validateRefreshToken(refreshToken);
    } catch (JwtService.InvalidRefreshTokenException ex) {
      throw new BadCredentialsException(ex);
    }

    // find user from validated refreshToken
    User user;
    try {
      user = userRepository.findById(java.util.UUID.fromString(validated.userId()))
          .orElseThrow(BadCredentialsException::new);
    } catch (IllegalArgumentException ex) {
      throw new BadCredentialsException(ex);
    }

    // return jswt tokens
    return tokens(user);
  }

  private Tokens tokens(User user) {

    String userId = user.getUserId().toString();

    AuthResponse body = new AuthResponse(
        jwtService.issueAccessToken(userId, user.getEmail()),
        "Bearer",
        jwtService.accessTtlSeconds());

    String refreshToken = jwtService.issueRefreshToken(userId, user.getEmail());
    return new Tokens(body, refreshToken);
  }

  private UserResponse toResponse(User user) {
    return new UserResponse(
        user.getUserId(),
        user.getEmail(),
        user.getFirstName(),
        user.getLastName(),
        user.getGroceryBudget());
  }

  public UserResponse getCurrentUser() {
    return toResponse(currentUser());
  }

  public UserResponse updateBudget(Double groceryBudget) {
    User user = currentUser();
    user.setGroceryBudget(groceryBudget);
    return toResponse(userRepository.save(user));
  }

  private User currentUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
      throw new BadCredentialsException();
    }
    try {
      return userRepository.findById(java.util.UUID.fromString(jwtAuth.getToken().getSubject()))
          .orElseThrow(UserNotFoundException::new);
    } catch (IllegalArgumentException ex) {
      throw new BadCredentialsException(ex);
    }
  }

  public record Tokens(AuthResponse body, String refreshToken) {
  }

  public static class EmailAlreadyExistsException extends RuntimeException {
  }

  public static class BadCredentialsException extends RuntimeException {
    public BadCredentialsException() {
      super();
    }

    public BadCredentialsException(Throwable cause) {
      super(cause);
    }
  }

  public static class UserNotFoundException extends RuntimeException {
  }
}
