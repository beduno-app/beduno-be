# Beduno Backend - Requirements Analysis

## Domain Model

### Core Entities

#### Worker
- `internalId` (unique, agency-scoped) - primary identifier used for lookup and check-in
- `firstName`, `lastName`
- `phone`
- `gender` (optional, used for constraint enforcement)
- `notes` / `tags` (structured, localizable)
- `status` (active / inactive / blacklisted)
- Belongs to: Agency (tenant)

#### Property
- `name`, `address`
- `type` (internal / partner)
- `genderRule` (mixed / male-only / female-only / per-room)
- `status` (active / blocked / maintenance)
- `notes`
- Belongs to: Agency (tenant); managed by Property Admin

#### Room
- `roomNumber` / `label`
- `capacity` (number of spots)
- `genderRule` (inherits from property or overrides)
- `blockedSpots` (number of spots temporarily unavailable)
- `status` (available / blocked / maintenance)
- `floor`, `notes`
- Belongs to: Property

#### Stay (the core assignment)
- `worker` -> Worker
- `property` -> Property
- `room` -> Room
- `dateFrom`, `dateTo` (nullable = open-ended)
- `status`: planned | expected_today | checked_in | checked_out | no_show | moved | cancelled
- `createdBy` (agency planner or property admin)
- `confirmedBy` (property-side role, nullable until confirmed)
- Audit fields: `createdAt`, `updatedAt`, `version`

### Supporting Entities

#### User / Account
- `email`, `name`, `language` (PL/EN/DE/UA/RU)
- `role`: agency_admin | agency_planner | property_admin | front_desk
- Scoped to: Agency (tenant) + optionally to specific Properties

#### AuditEvent
- `entityType`, `entityId`
- `action` (created / updated / deleted / checked_in / checked_out / moved / etc.)
- `performedBy` -> User
- `timestamp`
- `previousState`, `newState` (JSON snapshots or diffs)
- `reason` (predefined tag, localizable)

#### Tag / Reason (predefined, localizable)
- Examples: "Arrived late", "Docs missing", "Room conflict", "Sent to other property", "Maintenance"
- Used in stays, audit events, and notes

## Stay Status State Machine

```
planned ──> expected_today ──> checked_in ──> checked_out
   │              │                  │
   │              ├──> no_show       ├──> moved (creates new stay)
   │              │                  │
   └──> cancelled └──> cancelled     └──> cancelled (early departure)
```

- `planned` -> `expected_today`: automatic transition when dateFrom = today
- `expected_today` -> `checked_in`: front desk confirms arrival
- `expected_today` -> `no_show`: front desk marks no-show (manual or end-of-day batch)
- `checked_in` -> `checked_out`: front desk confirms departure
- `checked_in` -> `moved`: triggers new stay at destination room/property
- Any non-terminal -> `cancelled`: agency planner or admin cancels

## Constraint Engine (v1)

### Hard Constraints (block the operation)
- **Capacity**: room occupancy (checked_in stays) cannot exceed `capacity - blockedSpots`
- **Double-booking**: worker cannot have overlapping checked_in stays
- **Blocked room**: no new check-ins to a blocked room

### Soft Constraints (warn but allow override with reason)
- **Gender rule**: warn if mixed gender in a gender-restricted room
- **Blacklisted worker**: warn if worker has blacklist tag for this property
- **Over-plan**: warn if planned stays exceed capacity for a future date

### Constraint Response Model
```json
{
  "allowed": true,
  "hardViolations": [],
  "softViolations": [
    {
      "type": "GENDER_MISMATCH",
      "message": "Room 12 is marked female-only; worker is male",
      "overridable": true
    }
  ]
}
```

## Role-Based Access Control

### Permission Matrix

| Action | Agency Admin | Agency Planner | Property Admin | Front Desk |
|--------|:---:|:---:|:---:|:---:|
| Manage users | Yes | - | - | - |
| Manage workers | Yes | Yes | Read | Read |
| Manage properties | Yes | Read | Own properties | - |
| Manage rooms | Yes | Read | Own properties | Read |
| Create planned stays | Yes | Yes | Own properties | - |
| Check-in / check-out | - | - | Yes | Yes |
| Mark no-show | - | - | Yes | Yes |
| Move worker (room) | - | - | Yes | Yes |
| Cancel stay | Yes | Own stays | Own properties | - |
| View audit log | Yes | Own scope | Own properties | Own actions |
| Export reports | Yes | Yes | Own properties | - |
| Override soft constraint | Yes | - | Yes | - |

### Scoping Rules
- Agency Admin: full access within their agency (tenant)
- Agency Planner: all workers and stays within their agency
- Property Admin: scoped to assigned properties
- Front Desk: scoped to assigned properties, operational actions only

## API Requirements

### Arrivals Workflow
- `GET /stays/arrivals?propertyId=&date=` - list expected arrivals
- `POST /stays/{id}/check-in` - confirm arrival (with optional room override)
- `POST /stays/{id}/no-show` - mark no-show with reason

### Nightly Occupancy
- `GET /properties/{id}/occupancy?date=` - current occupancy by room
- `GET /properties/{id}/exceptions?date=` - over-capacity, unassigned workers, unknown
- `GET /properties/{id}/occupancy/export?format=csv&language=pl` - exportable report

### Inspection Mode
- `GET /properties/{id}/inspection?date=` - room-by-room roster
- `POST /properties/{id}/inspection` - submit discrepancy report (expected vs present)

### Bulk Operations
- `POST /workers/import` - CSV import of workers
- `POST /stays/bulk-assign` - assign multiple workers to rooms
- `POST /stays/bulk-checkout` - check out multiple workers

### CRUD
- Standard CRUD for workers, properties, rooms, stays
- Filtering, pagination, sorting on all list endpoints

## Multi-Tenancy

The system is multi-tenant from day 1. Each agency is a tenant. All data is scoped by `agencyId`. Row-level security ensures no cross-tenant data leaks.

## Internationalization (i18n)

- All API error messages and validation messages use message codes (not hardcoded strings)
- Predefined tags/reasons are stored with translations
- Export endpoints accept a `language` parameter
- The backend sends structured data; the frontend handles UI translation
- Backend-generated reports (CSV/PDF) support language selection

## Data & Compliance

- **GDPR**: store minimum PII, field-level access control, audit trail, retention policy
- **Audit**: every state change is logged with actor, timestamp, before/after state
- **Data minimization**: no ID scans in MVP, only references/metadata
- **Soft delete**: workers and stays are soft-deleted (retain for audit)

## Performance Requirements

- Answer "where is Worker X tonight?" in < 1 second (API response)
- Arrivals list for a property: < 500ms
- Bulk assign up to 200 workers: < 5 seconds
- CSV import of 1000 workers: < 30 seconds
- Concurrent users per agency: ~20-50
