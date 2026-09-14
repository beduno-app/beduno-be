package com.beduno.user;

import com.beduno.user.dto.CreateUserRequest;
import com.beduno.user.dto.UpdateUserRequest;
import com.beduno.user.dto.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "lastLoginAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    // tokenVersion is a revocation counter owned by UserService, never taken from a request:
    // accepting it from a client would let a caller reinstate a refresh token they had revoked.
    @Mapping(target = "tokenVersion", ignore = true)
    @Mapping(target = "assignedPropertyIds", expression = "java(toArray(request.assignedPropertyIds()))")
    User toEntity(CreateUserRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "lastLoginAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    // tokenVersion is a revocation counter owned by UserService, never taken from a request:
    // accepting it from a client would let a caller reinstate a refresh token they had revoked.
    @Mapping(target = "tokenVersion", ignore = true)
    @Mapping(target = "assignedPropertyIds", expression = "java(toArray(request.assignedPropertyIds()))")
    void updateEntity(UpdateUserRequest request, @MappingTarget User user);

    @Mapping(target = "assignedPropertyIds", expression = "java(toList(user.getAssignedPropertyIds()))")
    UserResponse toResponse(User user);

    default UUID[] toArray(List<UUID> list) {
        return list != null ? list.toArray(UUID[]::new) : new UUID[0];
    }

    default List<UUID> toList(UUID[] array) {
        return array != null ? Arrays.asList(array) : List.of();
    }
}
