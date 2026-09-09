package com.beduno.config;

import com.beduno.agency.Agency;
import com.beduno.agency.AgencyRepository;
import com.beduno.user.Role;
import com.beduno.user.User;
import com.beduno.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * There is no user-management API, so this runner is the only thing standing between a fresh
 * deployment and an API that answers 401 to everything with no way to obtain a token.
 */
class BootstrapRunnerTest {

    private static final String PASSWORD = "a-long-enough-password";

    private AgencyRepository agencyRepository;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private BootstrapProperties properties;

    @BeforeEach
    void setUp() {
        agencyRepository = mock(AgencyRepository.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        properties = new BootstrapProperties();
        properties.setEnabled(true);
        properties.setAgencyName("Design Partner");
        properties.setAdminEmail("admin@agency.pl");
        properties.setAdminPassword(PASSWORD);

        when(agencyRepository.save(any(Agency.class))).thenAnswer(call -> {
            var agency = call.getArgument(0, Agency.class);
            var idField = com.beduno.common.model.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(agency, UUID.randomUUID());
            return agency;
        });
    }

    private void run() {
        new BootstrapRunner(properties, agencyRepository, userRepository, passwordEncoder).run(null);
    }

    @Nested
    class WhenItShouldDoNothing {

        @Test
        void shouldCreateNothing_whenDisabled() {
            properties.setEnabled(false);
            // Misconfigured as well, to prove the disabled check comes first and cannot fail a boot.
            properties.setAdminEmail(null);

            run();

            verify(agencyRepository, never()).save(any());
            verify(userRepository, never()).save(any());
        }

        @Test
        void shouldCreateNothing_whenUsersAlreadyExist() {
            when(userRepository.count()).thenReturn(3L);

            run();

            verify(agencyRepository, never()).save(any());
            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    class WhenTheDatabaseIsEmpty {

        @Test
        void shouldCreateAgencyAndAdministrator() {
            when(userRepository.count()).thenReturn(0L);

            run();

            var agency = ArgumentCaptor.forClass(Agency.class);
            verify(agencyRepository).save(agency.capture());
            assertThat(agency.getValue().getName()).isEqualTo("Design Partner");

            var user = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(user.capture());
            assertThat(user.getValue().getEmail()).isEqualTo("admin@agency.pl");
            assertThat(user.getValue().getRole()).isEqualTo(Role.AGENCY_ADMIN);
            assertThat(user.getValue().getAgencyId()).isEqualTo(agency.getValue().getId());
        }

        @Test
        void shouldStoreThePasswordHashed() {
            when(userRepository.count()).thenReturn(0L);

            run();

            var user = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(user.capture());
            assertThat(user.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
            assertThat(passwordEncoder.matches(PASSWORD, user.getValue().getPasswordHash())).isTrue();
        }
    }

    @Nested
    class WhenMisconfigured {

        @Test
        void shouldFailStartup_whenPasswordIsMissing() {
            when(userRepository.count()).thenReturn(0L);
            properties.setAdminPassword(null);

            // Booting anyway would leave an API nobody can log into and no explanation on the
            // console for why.
            assertThatThrownBy(BootstrapRunnerTest.this::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD");
            verify(userRepository, never()).save(any());
        }

        @Test
        void shouldFailStartup_whenPasswordIsTooShort() {
            when(userRepository.count()).thenReturn(0L);
            properties.setAdminPassword("short");

            assertThatThrownBy(BootstrapRunnerTest.this::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 12 characters");
        }

        @Test
        void shouldReportEveryProblemAtOnce_whenSeveralAreMissing() {
            when(userRepository.count()).thenReturn(0L);
            properties.setAgencyName(" ");
            properties.setAdminEmail(null);
            properties.setAdminPassword(null);

            assertThatThrownBy(BootstrapRunnerTest.this::run)
                    .hasMessageContaining("BOOTSTRAP_AGENCY_NAME")
                    .hasMessageContaining("BOOTSTRAP_ADMIN_EMAIL")
                    .hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD");
        }
    }
}
