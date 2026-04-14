package com.bedok.stay;

import com.bedok.common.model.PageResponse;
import com.bedok.stay.dto.CheckInRequest;
import com.bedok.stay.dto.CreateStayRequest;
import com.bedok.stay.dto.NoShowRequest;
import com.bedok.stay.dto.StayResponse;
import com.bedok.stay.dto.UpdateStayRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stays")
@RequiredArgsConstructor
public class StayController {

    private final StayService stayService;

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<PageResponse<StayResponse>> findAll(
            @RequestParam(required = false) UUID workerId,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) StayStatus status,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @PageableDefault(sort = "date_from", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(stayService.findAll(workerId, propertyId, status, dateFrom, dateTo, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<StayResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(stayService.findById(id));
    }

    @GetMapping("/arrivals")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<List<StayResponse>> getArrivals(
            @RequestParam UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(stayService.getArrivals(propertyId, date != null ? date : LocalDate.now()));
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasAnyRole('PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<StayResponse> checkIn(@PathVariable UUID id,
                                                @Valid @RequestBody CheckInRequest request) {
        return ResponseEntity.ok(stayService.checkIn(id, request));
    }

    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAnyRole('PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<StayResponse> noShow(@PathVariable UUID id,
                                               @Valid @RequestBody NoShowRequest request) {
        return ResponseEntity.ok(stayService.noShow(id, request));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<StayResponse> create(@Valid @RequestBody CreateStayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stayService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<StayResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateStayRequest request) {
        return ResponseEntity.ok(stayService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        stayService.cancel(id);
        return ResponseEntity.noContent().build();
    }
}
