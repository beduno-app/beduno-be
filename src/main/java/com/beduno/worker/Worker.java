package com.beduno.worker;

import com.beduno.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "workers")
@Getter
@Setter
public class Worker extends BaseEntity {

    @Column(name = "agency_id", nullable = false)
    private UUID agencyId;

    @Column(name = "internal_id", nullable = false)
    private String internalId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;

    @Column
    private String nationality;

    @Column
    private String phone;

    @Column
    private String email;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(columnDefinition = "text[]")
    private String[] tags = {};

    @Column
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkerStatus status = WorkerStatus.ACTIVE;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
