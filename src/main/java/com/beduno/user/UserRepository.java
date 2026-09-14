package com.beduno.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {


    Optional<User> findByEmail(String email);

    Optional<User> findByIdAndAgencyId(UUID id, UUID agencyId);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    /**
     * Used to guard against removing the agency's last active admin, whether by deactivating them
     * or by changing their role away from AGENCY_ADMIN.
     */
    boolean existsByAgencyIdAndRoleAndStatusAndIdNot(UUID agencyId, Role role, UserStatus status, UUID id);

    @Query(value = """
            SELECT * FROM users u
            WHERE u.agency_id = :agencyId
            AND (CAST(:role AS VARCHAR) IS NULL OR u.role = CAST(:role AS VARCHAR))
            AND (CAST(:status AS VARCHAR) IS NULL OR u.status = CAST(:status AS VARCHAR))
            AND (CAST(:search AS VARCHAR) IS NULL
                 OR LOWER(u.first_name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(u.last_name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')))
            """,
            countQuery = """
            SELECT COUNT(*) FROM users u
            WHERE u.agency_id = :agencyId
            AND (CAST(:role AS VARCHAR) IS NULL OR u.role = CAST(:role AS VARCHAR))
            AND (CAST(:status AS VARCHAR) IS NULL OR u.status = CAST(:status AS VARCHAR))
            AND (CAST(:search AS VARCHAR) IS NULL
                 OR LOWER(u.first_name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(u.last_name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')))
            """,
            nativeQuery = true)
    Page<User> findAllByAgencyIdWithFilters(
            @Param("agencyId") UUID agencyId,
            @Param("role") String role,
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable);
}
