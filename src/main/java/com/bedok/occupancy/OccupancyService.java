package com.bedok.occupancy;

import com.bedok.common.security.TenantContext;
import com.bedok.occupancy.dto.OccupancyExceptionResponse;
import com.bedok.occupancy.dto.OccupantSummary;
import com.bedok.occupancy.dto.RoomOccupancyResponse;
import com.bedok.property.PropertyRepository;
import com.bedok.room.RoomRepository;
import com.bedok.stay.Stay;
import com.bedok.stay.StayRepository;
import com.bedok.stay.StayStatus;
import com.bedok.worker.Worker;
import com.bedok.worker.WorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OccupancyService {

    private static final List<StayStatus> CHECKED_IN_STATUS = List.of(StayStatus.CHECKED_IN);
    private static final List<StayStatus> ACTIVE_STATUSES = List.of(
            StayStatus.CHECKED_IN, StayStatus.EXPECTED_TODAY);

    private final StayRepository stayRepository;
    private final RoomRepository roomRepository;
    private final WorkerRepository workerRepository;
    private final PropertyRepository propertyRepository;

    @Transactional(readOnly = true)
    public List<RoomOccupancyResponse> getOccupancy(UUID propertyId, LocalDate date) {
        var agencyId = TenantContext.requireAgencyId();
        var rooms = roomRepository.findAllByAgencyIdAndPropertyId(agencyId, propertyId);
        var stays = stayRepository.findActiveStaysForPropertyOnDate(agencyId, propertyId, date, CHECKED_IN_STATUS);
        var workerMap = loadWorkers(stays, agencyId);

        var staysByRoom = stays.stream().collect(Collectors.groupingBy(Stay::getRoomId));

        return rooms.stream().map(room -> {
            var roomStays = staysByRoom.getOrDefault(room.getId(), List.of());
            var occupants = toOccupantSummaries(roomStays, workerMap);
            return new RoomOccupancyResponse(
                    room.getId(),
                    room.getName(),
                    room.getFloor(),
                    room.getCapacity(),
                    room.getBlockedSpots(),
                    occupants.size(),
                    occupants
            );
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<OccupancyExceptionResponse> getExceptions(UUID propertyId, LocalDate date) {
        var agencyId = TenantContext.requireAgencyId();
        var rooms = roomRepository.findAllByAgencyIdAndPropertyId(agencyId, propertyId);
        var stays = stayRepository.findActiveStaysForPropertyOnDate(agencyId, propertyId, date, ACTIVE_STATUSES);
        var workerMap = loadWorkers(stays, agencyId);

        var checkedInByRoom = stays.stream()
                .filter(s -> s.getStatus() == StayStatus.CHECKED_IN)
                .collect(Collectors.groupingBy(Stay::getRoomId));
        var expectedByRoom = stays.stream()
                .filter(s -> s.getStatus() == StayStatus.EXPECTED_TODAY)
                .collect(Collectors.groupingBy(Stay::getRoomId));

        var exceptions = new ArrayList<OccupancyExceptionResponse>();
        for (var room : rooms) {
            var checkedIn = checkedInByRoom.getOrDefault(room.getId(), List.of());
            var expected = expectedByRoom.getOrDefault(room.getId(), List.of());

            if (checkedIn.size() > room.availableSpots()) {
                exceptions.add(new OccupancyExceptionResponse(
                        room.getId(), room.getName(), "OVER_CAPACITY",
                        room.getCapacity(), room.getBlockedSpots(), checkedIn.size(),
                        toOccupantSummaries(checkedIn, workerMap)
                ));
            } else if (!expected.isEmpty()) {
                exceptions.add(new OccupancyExceptionResponse(
                        room.getId(), room.getName(), "PENDING_ARRIVAL",
                        room.getCapacity(), room.getBlockedSpots(), checkedIn.size(),
                        toOccupantSummaries(expected, workerMap)
                ));
            }
        }
        return exceptions;
    }

    private Map<UUID, Worker> loadWorkers(List<Stay> stays, UUID agencyId) {
        var workerIds = stays.stream().map(Stay::getWorkerId).distinct().toList();
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        return workerRepository.findAllById(workerIds).stream()
                .collect(Collectors.toMap(Worker::getId, w -> w));
    }

    private List<OccupantSummary> toOccupantSummaries(List<Stay> stays, Map<UUID, Worker> workerMap) {
        return stays.stream().map(s -> {
            var w = workerMap.get(s.getWorkerId());
            return new OccupantSummary(
                    s.getId(), s.getWorkerId(),
                    w != null ? w.getFirstName() : null,
                    w != null ? w.getLastName() : null
            );
        }).toList();
    }
}
