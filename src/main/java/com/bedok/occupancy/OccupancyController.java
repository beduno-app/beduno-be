package com.bedok.occupancy;

import com.bedok.occupancy.dto.OccupancyExceptionResponse;
import com.bedok.occupancy.dto.RoomOccupancyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
@RequiredArgsConstructor
public class OccupancyController {

    private final OccupancyService occupancyService;

    @GetMapping("/occupancy")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<List<RoomOccupancyResponse>> getOccupancy(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(occupancyService.getOccupancy(propertyId, date != null ? date : LocalDate.now()));
    }

    @GetMapping("/exceptions")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<List<OccupancyExceptionResponse>> getExceptions(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(occupancyService.getExceptions(propertyId, date != null ? date : LocalDate.now()));
    }
}
