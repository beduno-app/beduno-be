package com.beduno.agency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * The tenant discriminator every other repository filters on is this entity's own primary key, so
 * there is no agencyId predicate to add here.
 */
public interface AgencyRepository extends JpaRepository<Agency, UUID> {
}
