package com.beduno.worker;

import com.beduno.worker.dto.CreateWorkerRequest;
import com.beduno.worker.dto.UpdateWorkerRequest;
import com.beduno.worker.dto.WorkerResponse;
import com.beduno.worker.dto.WorkerSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Arrays;
import java.util.List;

@Mapper(componentModel = "spring")
public interface WorkerMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "tags", expression = "java(toArray(request.tags()))")
    Worker toEntity(CreateWorkerRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "agencyId", ignore = true)
    @Mapping(target = "internalId", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "tags", expression = "java(toArray(request.tags()))")
    void updateEntity(UpdateWorkerRequest request, @MappingTarget Worker worker);

    @Mapping(target = "tags", expression = "java(toList(worker.getTags()))")
    WorkerResponse toResponse(Worker worker);

    WorkerSummary toSummary(Worker worker);

    default String[] toArray(List<String> list) {
        return list != null ? list.toArray(String[]::new) : new String[0];
    }

    default List<String> toList(String[] array) {
        return array != null ? Arrays.asList(array) : List.of();
    }
}
