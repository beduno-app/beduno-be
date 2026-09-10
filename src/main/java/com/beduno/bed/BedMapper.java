package com.beduno.bed;

import com.beduno.bed.dto.BedResponse;
import com.beduno.bed.dto.UpdateBedRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface BedMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "roomId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(UpdateBedRequest request, @MappingTarget Bed bed);

    BedResponse toResponse(Bed bed);
}
