package com.authcenter.repository;

import com.authcenter.entity.OAuthBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OAuthBindingRepository extends JpaRepository<OAuthBinding, String> {
    
    Optional<OAuthBinding> findByProviderAndProviderUserId(String provider, String providerUserId);
    
    Optional<OAuthBinding> findByUserIdAndProvider(String userId, String provider);
}