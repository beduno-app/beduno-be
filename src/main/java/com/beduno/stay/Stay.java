package com.beduno.stay;

import com.beduno.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "stays")
@Getter
@Setter
public class Stay extends BaseEntity {

    @Column(name = "agency_id", nullable = false)
    private UUID agencyId;

    @Column(name = "worker_id", nullable = false)
    private UUID workerId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StayStatus status = StayStatus.PLANNED;

    @Column(name = "override_reason")
    private String overrideReason;

    @Column(name = "confirmed_by_user_id")
    private UUID confirmedByUserId;

    @Column(name = "no_show_reason")
    private String noShowReason;

    @Column
    private String notes;

    @Version
    @Column(nullable = false)
    private Long version;
}
