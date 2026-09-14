package com.beduno.config;

import com.beduno.agency.Agency;
import com.beduno.agency.AgencyRepository;
import com.beduno.audit.AuditAction;
import com.beduno.audit.AuditEntityType;
import com.beduno.audit.AuditService;
import com.beduno.bed.Bed;
import com.beduno.bed.BedRepository;
import com.beduno.property.Property;
import com.beduno.property.PropertyRepository;
import com.beduno.property.PropertyStatus;
import com.beduno.room.GenderRule;
import com.beduno.room.Room;
import com.beduno.room.RoomRepository;
import com.beduno.room.RoomStatus;
import com.beduno.stay.Stay;
import com.beduno.stay.StayRepository;
import com.beduno.stay.StayStatus;
import com.beduno.user.Role;
import com.beduno.user.User;
import com.beduno.user.UserRepository;
import com.beduno.user.UserStatus;
import com.beduno.worker.Gender;
import com.beduno.worker.Worker;
import com.beduno.worker.WorkerRepository;
import com.beduno.worker.WorkerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Populates its own demo agency with one row in every table, so a database is something a person
 * can click through instead of an empty shell that only proves auth works. Gated behind
 * {@link SeedProperties} the same way {@link BootstrapRunner} is gated.
 *
 * <p>Idempotency is scoped to the demo agency itself, not to the database as a whole: the guard
 * checks for {@link #DEMO_ADMIN_EMAIL} rather than {@code agencyRepository.count() > 0}, so this
 * is safe to enable against a database that already holds one or more real tenants — it adds its
 * own separate demo agency alongside them rather than refusing to run, and never touches rows it
 * did not create. The email check works because {@code users.email} is globally unique
 * (see {@code V8__unique_user_email.sql}), not just per-agency.
 *
 * <p>Runs standalone, independent of {@link BootstrapRunner} — it creates its own agency and
 * admin rather than assuming bootstrap already ran, so enabling seed alone (the local-dev default,
 * see {@code application-dev.yml}) is enough. Both write through repositories directly rather than
 * the service layer: there is no HTTP caller and no {@code TenantContext} to populate, and
 * {@link AuditService#log} takes the agency/actor ids as plain arguments, so the audit trail this
 * produces is real without needing either.
 */
@Slf4j
/** Runs after BootstrapRunner; see that class for why the order is declared rather than left to chance. */
@Component
@Order(2)
@RequiredArgsConstructor
public class SeedRunner implements ApplicationRunner {

    private static final String DEMO_ADMIN_EMAIL = "admin@demo.beduno.dev";
    private static final String DEMO_PASSWORD = "Demo12345678!";

    private final SeedProperties properties;
    private final AgencyRepository agencyRepository;
    private final UserRepository userRepository;
    private final WorkerRepository workerRepository;
    private final PropertyRepository propertyRepository;
    private final RoomRepository roomRepository;
    private final BedRepository bedRepository;
    private final StayRepository stayRepository;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }

        if (userRepository.existsByEmail(DEMO_ADMIN_EMAIL)) {
            log.info("Seed requested but the demo admin ({}) already exists; nothing to do. "
                    + "Unset BEDUNO_SEED_ENABLED / beduno.seed.enabled.", DEMO_ADMIN_EMAIL);
            return;
        }

        var agency = new Agency();
        agency.setName("Beduno Demo Agency");
        agency = agencyRepository.save(agency);
        var agencyId = agency.getId();

        var admin = createUser(agencyId, DEMO_ADMIN_EMAIL, "Agata", "Admin",
                Role.AGENCY_ADMIN, new UUID[0]);
        var actorId = admin.getId();
        audit(agencyId, actorId, AuditEntityType.USER, admin.getId(), snapshot(admin));

        createUser(agencyId, "planner@demo.beduno.dev", "Piotr", "Planner", Role.AGENCY_PLANNER, new UUID[0]);

        var sunrise = createProperty(agencyId, actorId, "Sunrise Residence", "ul. Słoneczna 12", "Warszawa");
        var riverside = createProperty(agencyId, actorId, "Riverside Lodge", "ul. Nadrzeczna 3", "Kraków");

        createUser(agencyId, "propertyadmin@demo.beduno.dev", "Paula", "PropertyAdmin",
                Role.PROPERTY_ADMIN, new UUID[]{sunrise.getId()});
        createUser(agencyId, "frontdesk@demo.beduno.dev", "Filip", "FrontDesk",
                Role.FRONT_DESK, new UUID[]{sunrise.getId()});

        var room101 = createRoom(agencyId, actorId, sunrise.getId(), "101", 1, GenderRule.MIXED, RoomStatus.ACTIVE);
        var room102 = createRoom(agencyId, actorId, sunrise.getId(), "102", 1, GenderRule.MALE_ONLY, RoomStatus.ACTIVE);
        var room103 = createRoom(agencyId, actorId, sunrise.getId(), "103", 1, GenderRule.FEMALE_ONLY, RoomStatus.ACTIVE);
        createRoom(agencyId, actorId, sunrise.getId(), "104", 1, GenderRule.MIXED, RoomStatus.BLOCKED);

        var room201 = createRoom(agencyId, actorId, riverside.getId(), "201", 2, GenderRule.MIXED, RoomStatus.ACTIVE);
        var room202 = createRoom(agencyId, actorId, riverside.getId(), "202", 2, GenderRule.MALE_ONLY, RoomStatus.ACTIVE);

        var bed101a = createBed(agencyId, actorId, room101.getId(), "1");
        var bed101b = createBed(agencyId, actorId, room101.getId(), "2");
        var bed102a = createBed(agencyId, actorId, room102.getId(), "1");
        var bed102b = createBed(agencyId, actorId, room102.getId(), "2");
        var bed103a = createBed(agencyId, actorId, room103.getId(), "1");
        var bed201a = createBed(agencyId, actorId, room201.getId(), "1");
        var bed201b = createBed(agencyId, actorId, room201.getId(), "2");
        createBed(agencyId, actorId, room202.getId(), "1");

        var janKowalski = createWorker(agencyId, actorId, "W-DEMO-001", "Jan", "Kowalski",
                Gender.MALE, "PL", List.of("electrician"), WorkerStatus.ACTIVE);
        var annaNowak = createWorker(agencyId, actorId, "W-DEMO-002", "Anna", "Nowak",
                Gender.FEMALE, "PL", List.of("cleaner"), WorkerStatus.ACTIVE);
        var petroIvanenko = createWorker(agencyId, actorId, "W-DEMO-003", "Petro", "Ivanenko",
                Gender.MALE, "UA", List.of("welder"), WorkerStatus.ACTIVE);
        var olenaShevchenko = createWorker(agencyId, actorId, "W-DEMO-004", "Olena", "Shevchenko",
                Gender.FEMALE, "UA", List.of("cook"), WorkerStatus.ACTIVE);
        var marekWisniewski = createWorker(agencyId, actorId, "W-DEMO-005", "Marek", "Wiśniewski",
                Gender.MALE, "PL", List.of("electrician", "supervisor"), WorkerStatus.ACTIVE);
        var kasiaZielinska = createWorker(agencyId, actorId, "W-DEMO-006", "Katarzyna", "Zielińska",
                Gender.FEMALE, "PL", List.of("cleaner"), WorkerStatus.ACTIVE);
        createWorker(agencyId, actorId, "W-DEMO-007", "Dmytro", "Bondarenko",
                Gender.MALE, "UA", List.of("welder"), WorkerStatus.INACTIVE);
        var irynaMelnyk = createWorker(agencyId, actorId, "W-DEMO-008", "Iryna", "Melnyk",
                Gender.FEMALE, "UA", List.of("cook"), WorkerStatus.ACTIVE);

        var today = LocalDate.now(clock);

        createStay(agencyId, actorId, janKowalski.getId(), sunrise.getId(), room101.getId(), bed101a.getId(),
                today.minusDays(2), today.plusDays(5), StayStatus.CHECKED_IN, actorId, null);
        createStay(agencyId, actorId, annaNowak.getId(), sunrise.getId(), room101.getId(), bed101b.getId(),
                today.plusDays(3), today.plusDays(10), StayStatus.PLANNED, null, null);
        createStay(agencyId, actorId, petroIvanenko.getId(), sunrise.getId(), room102.getId(), bed102a.getId(),
                today, today.plusDays(7), StayStatus.EXPECTED_TODAY, null, null);
        createStay(agencyId, actorId, marekWisniewski.getId(), sunrise.getId(), room102.getId(), bed102b.getId(),
                today.minusDays(14), today.minusDays(7), StayStatus.CHECKED_OUT, actorId, null);
        createStay(agencyId, actorId, olenaShevchenko.getId(), sunrise.getId(), room103.getId(), bed103a.getId(),
                today.plusDays(5), today.plusDays(8), StayStatus.CANCELLED, null, null);
        createStay(agencyId, actorId, kasiaZielinska.getId(), riverside.getId(), room201.getId(), bed201a.getId(),
                today.minusDays(1), today.plusDays(6), StayStatus.NO_SHOW, null, "Did not arrive as scheduled");
        createStay(agencyId, actorId, irynaMelnyk.getId(), riverside.getId(), room201.getId(), bed201b.getId(),
                today.plusDays(1), today.plusDays(20), StayStatus.PLANNED, null, null);

        log.info("Seeded demo agency '{}' ({}) with 4 users, 2 properties, 6 rooms, 8 beds, "
                + "8 workers, and 7 stays. Demo password for every seeded user: {}",
                agency.getName(), agencyId, DEMO_PASSWORD);
    }

    private User createUser(UUID agencyId, String email, String firstName, String lastName,
            Role role, UUID[] assignedPropertyIds) {
        var user = new User();
        user.setAgencyId(agencyId);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setRole(role);
        user.setLanguage("EN");
        user.setAssignedPropertyIds(assignedPropertyIds);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private Property createProperty(UUID agencyId, UUID actorId, String name, String address, String city) {
        var property = new Property();
        property.setAgencyId(agencyId);
        property.setName(name);
        property.setAddress(address);
        property.setCity(city);
        property.setStatus(PropertyStatus.ACTIVE);
        property = propertyRepository.save(property);
        audit(agencyId, actorId, AuditEntityType.PROPERTY, property.getId(), Map.of("name", name, "city", city));
        return property;
    }

    private Room createRoom(UUID agencyId, UUID actorId, UUID propertyId, String roomNumber, Integer floor,
            GenderRule genderRule, RoomStatus status) {
        var room = new Room();
        room.setAgencyId(agencyId);
        room.setPropertyId(propertyId);
        room.setRoomNumber(roomNumber);
        room.setFloor(floor);
        room.setGenderRule(genderRule);
        room.setStatus(status);
        room = roomRepository.save(room);
        audit(agencyId, actorId, AuditEntityType.ROOM, room.getId(),
                Map.of("roomNumber", roomNumber, "genderRule", genderRule.name()));
        return room;
    }

    private Bed createBed(UUID agencyId, UUID actorId, UUID roomId, String label) {
        var bed = new Bed();
        bed.setAgencyId(agencyId);
        bed.setRoomId(roomId);
        bed.setLabel(label);
        bed = bedRepository.save(bed);
        audit(agencyId, actorId, AuditEntityType.BED, bed.getId(), Map.of("roomId", roomId, "label", label));
        return bed;
    }

    private Worker createWorker(UUID agencyId, UUID actorId, String internalId, String firstName, String lastName,
            Gender gender, String nationality, List<String> tags, WorkerStatus status) {
        var worker = new Worker();
        worker.setAgencyId(agencyId);
        worker.setInternalId(internalId);
        worker.setFirstName(firstName);
        worker.setLastName(lastName);
        worker.setGender(gender);
        worker.setNationality(nationality);
        worker.setTags(tags.toArray(String[]::new));
        worker.setStatus(status);
        worker = workerRepository.save(worker);
        audit(agencyId, actorId, AuditEntityType.WORKER, worker.getId(),
                Map.of("internalId", internalId, "firstName", firstName, "lastName", lastName));
        return worker;
    }

    private Stay createStay(UUID agencyId, UUID actorId, UUID workerId, UUID propertyId, UUID roomId, UUID bedId,
            LocalDate dateFrom, LocalDate dateTo, StayStatus status, UUID confirmedByUserId, String noShowReason) {
        var stay = new Stay();
        stay.setAgencyId(agencyId);
        stay.setWorkerId(workerId);
        stay.setPropertyId(propertyId);
        stay.setRoomId(roomId);
        stay.setBedId(bedId);
        stay.setBedAutoAssigned(false);
        stay.setDateFrom(dateFrom);
        stay.setDateTo(dateTo);
        stay.setStatus(status);
        stay.setConfirmedByUserId(confirmedByUserId);
        stay.setNoShowReason(noShowReason);
        stay = stayRepository.save(stay);
        audit(agencyId, actorId, AuditEntityType.STAY, stay.getId(),
                Map.of("workerId", workerId, "bedId", bedId, "status", status.name()));
        return stay;
    }

    private void audit(UUID agencyId, UUID actorId, AuditEntityType type, UUID entityId, Map<String, Object> newState) {
        auditService.log(agencyId, actorId, type, entityId, AuditAction.CREATED, null, newState, "seed");
    }

    private Map<String, Object> snapshot(User user) {
        return Map.of("email", user.getEmail(), "role", user.getRole().name());
    }
}
