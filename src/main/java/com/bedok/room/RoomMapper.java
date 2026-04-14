package com.bedok.room;

import com.bedok.room.dto.CreateRoomRequest;
import com.bedok.room.dto.RoomResponse;
import com.bedok.room.dto.UpdateRoomRequest;
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
    @Mapping(target = "genderRule", defaultExpression = "java(com.bedok.room.GenderRule.ANY)")
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
