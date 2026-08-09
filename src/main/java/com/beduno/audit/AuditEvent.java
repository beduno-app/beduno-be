package com.beduno.audit;

import com.beduno.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
@Getter
@Setter
public class AuditEvent extends BaseEntity {

    @Column(name = "agency_id", nullable = false)
    private UUID agencyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private AuditEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private AuditAction action;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Convert(converter = JsonbConverter.class)
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "previous_state", columnDefinition = "jsonb")
    private Map<String, Object> previousState;

    @Convert(converter = JsonbConverter.class)
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "new_state", columnDefinition = "jsonb")
    private Map<String, Object> newState;

    @Column(name = "reason")
    private String reason;
}
