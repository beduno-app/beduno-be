package com.beduno.user;

import com.beduno.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity {

    @Column(name = "agency_id", nullable = false)
    private UUID agencyId;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private String language = "PL";

    @Column(name = "assigned_property_ids", columnDefinition = "uuid[]")
    private UUID[] assignedPropertyIds = {};

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * The generation a refresh token must carry to still be accepted. Bumping it invalidates every
     * refresh token issued for this user so far, which is the only way to cut short a stateless
     * seven-day credential short of rotating the signing key for every tenant at once.
     */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;
}
