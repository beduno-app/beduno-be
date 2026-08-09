package com.beduno.audit;

import com.beduno.audit.dto.AuditEventResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AuditMapper {

    AuditEventResponse toResponse(AuditEvent event);
}
