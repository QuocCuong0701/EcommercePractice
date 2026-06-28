package com.ecommercebe.domain.user;

import com.ecommercebe.domain.JwtService;
import com.ecommercebe.dto.AuthResponse;
import com.ecommercebe.dto.LoginRequest;
import com.ecommercebe.dto.RefreshRequest;
import com.ecommercebe.dto.RegisterRequest;
import com.ecommercebe.dto.enumtype.Role;
import com.ecommercebe.dto.enumtype.UserStatus;
import com.ecommercebe.exception.AccountDisabledException;
import com.ecommercebe.exception.BadCredentialsException;
import com.ecommercebe.exception.EmailAlreadyExistsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String BLACKLIST_PREFIX = "blacklist:refresh:";

    @Transactional(rollbackFor = Exception.class)
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException("Email đã được sử dụng");
        }
        User user = new User();
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFullName(request.getFullName());
        userRepository.save(user);
        return generateTokens(user);
    }

    private AuthResponse generateTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken, user.getEmail());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("Email hoặc mật khẩu sai"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Email hoặc mật khẩu sai");
        }

        if (!Objects.equals(user.getStatus(), UserStatus.ACTIVE)) {
            throw new AccountDisabledException("Tài khoản bị khóa");
        }

        return generateTokens(user);
    }

    public AuthResponse refresh(RefreshRequest request) {
        String token = request.getRefreshToken();

        String blacklist = BLACKLIST_PREFIX + token;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blacklist))) {
            throw new BadCredentialsException("Refresh token đã bị thu hồi");
        }

        if (!jwtService.validateToken(token)) {
            throw new BadCredentialsException("Refresh token không hợp lệ");
        }

        UUID userId = jwtService.extractUserId(token);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("User không tồn tại"));

        if (!Objects.equals(user.getStatus(), UserStatus.ACTIVE)) {
            throw new AccountDisabledException("Tài khoản bị khóa");
        }

        log.info("Token refresh cho user: {}", user.getEmail());
        return generateTokens(user);
    }

    public void logout(String refreshToken) {
        String key = BLACKLIST_PREFIX + refreshToken;
        redisTemplate.opsForValue().set(key, "revoked", Duration.ofDays(7));
    }
}
