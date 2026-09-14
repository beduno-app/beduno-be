package com.beduno.stay;

import com.beduno.stay.dto.CreateStayRequest;
import com.beduno.stay.dto.StayResponse;
import com.beduno.stay.dto.UpdateStayRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface StayMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "bedId", ignore = true)
    @Mapping(target = "bedAutoAssigned", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "confirmedByUserId", ignore = true)
    @Mapping(target = "noShowReason", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Stay toEntity(CreateStayRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "workerId", ignore = true)
    @Mapping(target = "propertyId", ignore = true)
    @Mapping(target = "bedId", ignore = true)
    @Mapping(target = "bedAutoAssigned", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "confirmedByUserId", ignore = true)
    @Mapping(target = "noShowReason", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(UpdateStayRequest request, @MappingTarget Stay stay);

    StayResponse toResponse(Stay stay);

}
