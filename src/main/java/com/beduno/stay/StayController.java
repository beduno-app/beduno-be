package com.beduno.stay;

import com.beduno.common.model.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.beduno.stay.dto.BulkAssignRequest;
import com.beduno.stay.dto.BulkAssignResult;
import com.beduno.stay.dto.BulkCheckoutRequest;
import com.beduno.stay.dto.BulkCheckoutResult;
import com.beduno.stay.dto.CheckInRequest;
import com.beduno.stay.dto.CheckOutRequest;
import com.beduno.stay.dto.CreateStayRequest;
import com.beduno.stay.dto.MoveRequest;
import com.beduno.stay.dto.NoShowRequest;
import com.beduno.stay.dto.StayResponse;
import com.beduno.stay.dto.UpdateStayRequest;
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

@Tag(name = "Stays", description = "Stay lifecycle: plan, check-in, check-out, move, bulk operations")
@RestController
@RequestMapping("/api/v1/stays")
@RequiredArgsConstructor
public class StayController {

    private final StayService stayService;

    @Operation(summary = "List stays", description = "Paginated list filterable by worker, property, status, and date range")
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

    @Operation(summary = "Get stay by ID")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<StayResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(stayService.findById(id));
    }

    @Operation(summary = "Arrivals list", description = "Returns expected_today stays for a property on a given date")
    @GetMapping("/arrivals")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<List<StayResponse>> getArrivals(
            @RequestParam UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(stayService.getArrivals(propertyId, date != null ? date : LocalDate.now()));
    }

    @Operation(summary = "Check in", description = "Transitions an expected_today stay to checked_in. Runs constraint engine. Optionally overrides room.")
    @ApiResponse(responseCode = "422", description = "Constraint violation — re-submit with overrideReason to force")
    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasAnyRole('PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<StayResponse> checkIn(@PathVariable UUID id,
                                                @Valid @RequestBody CheckInRequest request) {
        return ResponseEntity.ok(stayService.checkIn(id, request));
    }

    @Operation(summary = "No-show", description = "Mark a stay as no_show with a reason tag")
    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAnyRole('PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<StayResponse> noShow(@PathVariable UUID id,
                                               @Valid @RequestBody NoShowRequest request) {
        return ResponseEntity.ok(stayService.noShow(id, request));
    }

    @Operation(summary = "Check out", description = "Transitions a checked_in stay to checked_out. Sets actual departure date if different from planned.")
    @PostMapping("/{id}/check-out")
    @PreAuthorize("hasAnyRole('PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<StayResponse> checkOut(@PathVariable UUID id,
                                                 @Valid @RequestBody CheckOutRequest request) {
        return ResponseEntity.ok(stayService.checkOut(id, request));
    }

    @Operation(summary = "Move worker to another room", description = "Atomic operation: checks out from current room, creates new stay in target room")
    @ApiResponse(responseCode = "422", description = "Constraint violation on target room")
    @PostMapping("/{id}/move")
    @PreAuthorize("hasAnyRole('PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<StayResponse> move(@PathVariable UUID id,
                                             @Valid @RequestBody MoveRequest request) {
        return ResponseEntity.ok(stayService.move(id, request));
    }

    @Operation(summary = "Plan a stay", description = "Creates a planned stay. Runs constraint engine — returns 422 with violations if hard constraints are breached.")
    @ApiResponse(responseCode = "201", description = "Stay planned")
    @ApiResponse(responseCode = "422", description = "Constraint violation")
    @PostMapping
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<StayResponse> create(@Valid @RequestBody CreateStayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stayService.create(request));
    }

    @Operation(summary = "Update stay", description = "Change dates or room. Runs constraint engine.")
    @ApiResponse(responseCode = "422", description = "Constraint violation")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<StayResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateStayRequest request) {
        return ResponseEntity.ok(stayService.update(id, request));
    }

    @Operation(summary = "Cancel stay")
    @ApiResponse(responseCode = "204", description = "Stay cancelled")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        stayService.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Bulk assign stays", description = "Creates multiple planned stays in one call. Returns per-item results including constraint violations.")
    @PostMapping("/bulk-assign")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<BulkAssignResult> bulkAssign(@Valid @RequestBody BulkAssignRequest request) {
        return ResponseEntity.ok(stayService.bulkAssign(request));
    }

    @Operation(summary = "Bulk checkout stays", description = "Checks out multiple checked_in stays in one call. Returns per-item results.")
    @PostMapping("/bulk-checkout")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<BulkCheckoutResult> bulkCheckout(@Valid @RequestBody BulkCheckoutRequest request) {
        return ResponseEntity.ok(stayService.bulkCheckout(request));
    }
}
