package com.beduno.config;

import com.beduno.agency.AgencyRepository;
import com.beduno.user.Role;
import com.beduno.user.UserRepository;
import com.beduno.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the real bean against a real database. The runner is the only thing standing between a
 * fresh deployment and an API that answers 401 to everything with no way to obtain a token, and
 * its whole contract is about what is or is not already in the users table — which a mocked
 * repository cannot tell you.
 *
 * <p>Its own database, because these tests empty the schema between cases and the shared one in
 * {@code IntegrationTestBase} carries other suites' fixtures. Shared with the seed-runner test
 * through {@link RunnerTestBase}: see there for why one is enough for both.
 */
class BootstrapRunnerIntegrationTest extends RunnerTestBase {

    private static final String PASSWORD = "a-long-enough-password";



    @Autowired
    private BootstrapRunner runner;

    @Autowired
    private BootstrapProperties properties;

    @Autowired
    private AgencyRepository agencyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyDatabaseAndConfigure() {
        jdbcTemplate.update("TRUNCATE users, agencies CASCADE");
        properties.setEnabled(true);
        properties.setAgencyName("Design Partner");
        properties.setAdminEmail("admin@agency.pl");
        properties.setAdminPassword(PASSWORD);
        properties.setAdminFirstName("Agency");
        properties.setAdminLastName("Admin");
        properties.setAdminLanguage("PL");
    }

    @Nested
    class OnAnEmptyDatabase {

        @Test
        void shouldCreateAgencyAndAdministrator() {
            runner.run(null);

            var agency = agencyRepository.findAll().getFirst();
            assertThat(agency.getName()).isEqualTo("Design Partner");
            assertThat(agency.getStatus()).isEqualTo("ACTIVE");

            var admin = userRepository.findByEmail("admin@agency.pl").orElseThrow();
            assertThat(admin.getAgencyId()).isEqualTo(agency.getId());
            assertThat(admin.getRole()).isEqualTo(Role.AGENCY_ADMIN);
            assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(admin.getLanguage()).isEqualTo("PL");
            assertThat(admin.getAssignedPropertyIds()).isEmpty();
        }

        @Test
        void shouldStoreThePasswordHashed() {
            runner.run(null);

            var admin = userRepository.findByEmail("admin@agency.pl").orElseThrow();
            assertThat(admin.getPasswordHash()).isNotEqualTo(PASSWORD);
            assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
        }
    }

    @Nested
    class WhenItShouldDoNothing {

        @Test
        void shouldNotCreateASecondAgency_whenRunAgain() {
            runner.run(null);
            // A restart loop re-runs this on every boot; it must not accumulate agencies.
            properties.setAgencyName("Someone Else");
            properties.setAdminEmail("someone-else@agency.pl");

            runner.run(null);

            assertThat(agencyRepository.count()).isEqualTo(1);
            assertThat(userRepository.count()).isEqualTo(1);
            assertThat(userRepository.findByEmail("someone-else@agency.pl")).isEmpty();
        }

        @Test
        void shouldCreateNothing_whenDisabled() {
            properties.setEnabled(false);
            // Misconfigured too, to prove the disabled check runs first and cannot fail a boot.
            properties.setAdminPassword(null);

            runner.run(null);

            assertThat(userRepository.count()).isZero();
            assertThat(agencyRepository.count()).isZero();
        }
    }

    @Nested
    class WhenMisconfigured {

        @Test
        void shouldFailStartupAndWriteNothing_whenPasswordIsTooShort() {
            properties.setAdminPassword("short");

            assertThatThrownBy(() -> runner.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 12 characters");

            // Booting anyway would leave an API nobody can log into and no explanation on the
            // console; writing a half-built tenant would be worse still.
            assertThat(agencyRepository.count()).isZero();
            assertThat(userRepository.count()).isZero();
        }

        @Test
        void shouldReportEveryProblemAtOnce_whenSeveralAreMissing() {
            properties.setAgencyName(" ");
            properties.setAdminEmail(null);
            properties.setAdminPassword(null);

            assertThatThrownBy(() -> runner.run(null))
                    .hasMessageContaining("BOOTSTRAP_AGENCY_NAME")
                    .hasMessageContaining("BOOTSTRAP_ADMIN_EMAIL")
                    .hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD");
        }
    }
}
