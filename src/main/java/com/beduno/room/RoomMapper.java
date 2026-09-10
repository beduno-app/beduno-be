package com.beduno.room;

import com.beduno.room.dto.CreateRoomRequest;
import com.beduno.room.dto.RoomResponse;
import com.beduno.room.dto.UpdateRoomRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface RoomMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "propertyId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "genderRule", defaultExpression = "java(com.beduno.room.GenderRule.MIXED)")
    Room toEntity(CreateRoomRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "propertyId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(UpdateRoomRequest request, @MappingTarget Room room);

    @Mapping(target = "availableSpots", expression = "java(room.availableSpots())")
    RoomResponse toResponse(Room room);
}
