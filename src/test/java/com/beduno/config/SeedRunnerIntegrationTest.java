package com.beduno.config;

import com.beduno.agency.Agency;
import com.beduno.agency.AgencyRepository;
import com.beduno.bed.BedRepository;
import com.beduno.property.PropertyRepository;
import com.beduno.room.RoomRepository;
import com.beduno.stay.StayRepository;
import com.beduno.user.Role;
import com.beduno.user.UserRepository;
import com.beduno.worker.WorkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real bean against a real database, the same rationale as {@link
 * BootstrapRunnerIntegrationTest}: whether the seed ran is entirely about what rows already exist,
 * which a mocked repository can't tell you.
 *
 * <p>Shares {@link RunnerTestBase}'s database with that class: these tests empty the schema
 * between cases, which the shared container in {@code IntegrationTestBase} cannot afford.
 */
class SeedRunnerIntegrationTest extends RunnerTestBase {



    @Autowired
    private SeedRunner runner;

    @Autowired
    private SeedProperties properties;

    @Autowired
    private AgencyRepository agencyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BedRepository bedRepository;

    @Autowired
    private StayRepository stayRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyDatabaseAndConfigure() {
        jdbcTemplate.update("TRUNCATE stays, beds, rooms, properties, workers, users, agencies CASCADE");
        properties.setEnabled(true);
    }

    @Nested
    class OnAnEmptyDatabase {

        @Test
        void shouldPopulateEveryTable() {
            runner.run(null);

            assertThat(agencyRepository.count()).isEqualTo(1);
            assertThat(userRepository.count()).isEqualTo(4);
            assertThat(propertyRepository.count()).isEqualTo(2);
            assertThat(roomRepository.count()).isEqualTo(6);
            assertThat(bedRepository.count()).isEqualTo(8);
            assertThat(workerRepository.count()).isEqualTo(8);
            assertThat(stayRepository.count()).isEqualTo(7);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events", Long.class))
                    .isPositive();
        }

        @Test
        void shouldCreateOneUserPerRole() {
            runner.run(null);

            var roles = userRepository.findAll().stream().map(u -> u.getRole()).distinct().toList();
            assertThat(roles).containsExactlyInAnyOrder(
                    Role.AGENCY_ADMIN, Role.AGENCY_PLANNER, Role.PROPERTY_ADMIN, Role.FRONT_DESK);
        }

        @Test
        void shouldScopeEveryRowToTheSameAgency() {
            runner.run(null);

            var agencyId = agencyRepository.findAll().getFirst().getId();
            assertThat(userRepository.findAll()).allMatch(u -> u.getAgencyId().equals(agencyId));
            assertThat(workerRepository.findAll()).allMatch(w -> w.getAgencyId().equals(agencyId));
            assertThat(propertyRepository.findAll()).allMatch(p -> p.getAgencyId().equals(agencyId));
            assertThat(roomRepository.findAll()).allMatch(r -> r.getAgencyId().equals(agencyId));
            assertThat(bedRepository.findAll()).allMatch(b -> b.getAgencyId().equals(agencyId));
            assertThat(stayRepository.findAll()).allMatch(s -> s.getAgencyId().equals(agencyId));
        }
    }

    @Nested
    class AlongsideAnExistingTenant {

        @Test
        void shouldAddItsOwnAgency_whenAnotherAgencyAlreadyExists() {
            var realAgency = new Agency();
            realAgency.setName("Real Tenant Agency");
            agencyRepository.save(realAgency);

            runner.run(null);

            assertThat(agencyRepository.count()).isEqualTo(2);
            assertThat(userRepository.count()).isEqualTo(4);
        }
    }

    @Nested
    class WhenItShouldDoNothing {

        @Test
        void shouldNotSeedTwice_whenRunAgain() {
            runner.run(null);
            runner.run(null);

            assertThat(agencyRepository.count()).isEqualTo(1);
            assertThat(userRepository.count()).isEqualTo(4);
        }

        @Test
        void shouldCreateNothing_whenDisabled() {
            properties.setEnabled(false);

            runner.run(null);

            assertThat(agencyRepository.count()).isZero();
            assertThat(userRepository.count()).isZero();
        }
    }
}
