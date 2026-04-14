package com.bedok.property;

import com.bedok.property.dto.CreatePropertyRequest;
import com.bedok.property.dto.PropertyResponse;
import com.bedok.property.dto.UpdatePropertyRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface PropertyMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Property toEntity(CreatePropertyRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(UpdatePropertyRequest request, @MappingTarget Property property);

    PropertyResponse toResponse(Property property);
}
