package com.bedok.audit;

import com.bedok.audit.dto.AuditEventResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AuditMapper {

    AuditEventResponse toResponse(AuditEvent event);
}
