package com.beduno.config;

import com.beduno.agency.Agency;
import com.beduno.agency.AgencyRepository;
import com.beduno.user.Role;
import com.beduno.user.User;
import com.beduno.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

/**
 * Creates the first agency and its AGENCY_ADMIN from the environment, once, on an empty database.
 *
 * <p>A seed migration would have been the obvious alternative and is the wrong tool twice over: a
 * migration runs exactly once ever, so a first boot with the variables absent would burn the only
 * chance to create the account, and the credentials would have to live in a file committed to this
 * repository. This runs on every startup, does nothing whenever a user already exists, and reads
 * the credentials from the environment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapRunner implements ApplicationRunner {

    /** Long enough that the account guarding every tenant is not brute-forceable. */
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final BootstrapProperties properties;
    private final AgencyRepository agencyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }

        var existingUsers = userRepository.count();
        if (existingUsers > 0) {
            log.info("Bootstrap requested but {} user(s) already exist; nothing to do. "
                    + "Unset the BOOTSTRAP_* variables.", existingUsers);
            return;
        }

        // Startup fails rather than continuing, because the alternative is an API that answers
        // every request with 401 and gives no clue why.
        requireComplete();

        var agency = new Agency();
        agency.setName(properties.getAgencyName().trim());
        agency = agencyRepository.save(agency);

        var admin = new User();
        admin.setAgencyId(agency.getId());
        admin.setEmail(properties.getAdminEmail().trim());
        admin.setPasswordHash(passwordEncoder.encode(properties.getAdminPassword()));
        admin.setFirstName(properties.getAdminFirstName().trim());
        admin.setLastName(properties.getAdminLastName().trim());
        admin.setRole(Role.AGENCY_ADMIN);
        admin.setLanguage(properties.getAdminLanguage().trim());
        userRepository.save(admin);

        // The email is deliberately absent: it is PII, this line goes to the container log, and
        // the operator who set BOOTSTRAP_ADMIN_EMAIL already knows which address they chose.
        log.info("Bootstrapped agency '{}' ({}) with its first AGENCY_ADMIN",
                agency.getName(), agency.getId());
        log.warn("Remove the BOOTSTRAP_* variables from the environment now: they are a standing "
                + "copy of an administrator password, re-read at every startup.");
    }

    private void requireComplete() {
        var problems = new ArrayList<String>();
        if (isBlank(properties.getAgencyName())) {
            problems.add("beduno.bootstrap.agency-name (BOOTSTRAP_AGENCY_NAME) is required");
        }
        if (isBlank(properties.getAdminEmail())) {
            problems.add("beduno.bootstrap.admin-email (BOOTSTRAP_ADMIN_EMAIL) is required");
        }
        var password = properties.getAdminPassword();
        if (isBlank(password)) {
            problems.add("beduno.bootstrap.admin-password (BOOTSTRAP_ADMIN_PASSWORD) is required");
        } else if (password.length() < MIN_PASSWORD_LENGTH) {
            problems.add("beduno.bootstrap.admin-password must be at least "
                    + MIN_PASSWORD_LENGTH + " characters");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Bootstrap is enabled but misconfigured: "
                    + String.join("; ", problems));
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
