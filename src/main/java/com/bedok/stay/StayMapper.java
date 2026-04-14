package com.bedok.stay;

import com.bedok.stay.dto.CreateStayRequest;
import com.bedok.stay.dto.StayResponse;
import com.bedok.stay.dto.StaySummary;
import com.bedok.stay.dto.UpdateStayRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface StayMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "confirmedByUserId", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Stay toEntity(CreateStayRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "workerId", ignore = true)
    @Mapping(target = "propertyId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "confirmedByUserId", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(UpdateStayRequest request, @MappingTarget Stay stay);

    StayResponse toResponse(Stay stay);

    StaySummary toSummary(Stay stay);
}
