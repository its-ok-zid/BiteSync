package com.bitesync.userservice.repository;

import com.bitesync.userservice.model.RefreshToken;
import com.bitesync.userservice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByToken(String token);
    void deleteByUser(User user);
    void deleteByUserId(UUID userId);
}