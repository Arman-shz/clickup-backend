--liquibase formatted sql

-- 001 made `email` NOT NULL because every UserProfile example in the spec carries one.
-- Registration changed that: POST /api/auth/register collects a full name, a student id
-- and a password, and nothing else, so an account now exists before it has an address.
--
-- Storing a synthesised one (99100111@arman.local) would put a fabricated address into
-- the same column as the real ones, indistinguishable from them. The column is made
-- optional instead, and PUT /api/users/me fills it in later.
--
-- Nothing in the spec forbids this: components/schemas/UserProfile declares no `required`
-- list at all, so a null email still satisfies the documented contract.
--
-- UNIQUE is deliberately kept. Postgres allows any number of NULLs in a unique index, so
-- unregistered addresses do not collide, while two accounts still cannot claim one address.

--changeset arman:008-email-optional
--comment: Email is no longer known at registration time, so it becomes optional.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
--rollback ALTER TABLE users ALTER COLUMN email SET NOT NULL;
