-- A revocation lever for refresh tokens.
--
-- Refresh tokens are stateless and live for seven days. Nothing was ever compared against the
-- database, so a leaked one kept minting fresh pairs for its full lifetime no matter what the
-- user or an administrator did; the only way to stop it was rotating JWT_SECRET, which logs out
-- every tenant at once.
--
-- One integer, carried as the "tv" claim and checked on refresh, is enough: bumping it
-- invalidates every refresh token issued for that user before the bump, and nothing else.
-- The default of 0 matches what the provider reads from a token minted before the claim existed,
-- so existing sessions survive the migration and are revoked the first time the column moves.
ALTER TABLE users
    ADD COLUMN token_version INT NOT NULL DEFAULT 0;
