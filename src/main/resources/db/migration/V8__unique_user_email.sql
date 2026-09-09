-- Login resolves a user by email alone (UserRepository.findByEmail), but the only uniqueness
-- guarantee was UNIQUE (agency_id, email). The same address could therefore exist legitimately in
-- two agencies, and login would have picked between them by whichever row Postgres returned
-- first -- authenticating someone into a tenant they did not ask for, non-deterministically.
--
-- Making the address globally unique keeps the login contract as it stands: email plus password,
-- with no tenant selector for a caller to get wrong. The alternative, scoping login by agency,
-- needs an agency identifier at the login call that no client sends and no endpoint exposes.
--
-- uq_users_email_agency is left in place. It is redundant under this constraint, and that is the
-- point: if one person ever needs accounts at two agencies, dropping uq_users_email is the whole
-- revert.
ALTER TABLE users
    ADD CONSTRAINT uq_users_email UNIQUE (email);
