-- ---------------------------------------------------------------------------
-- Single-use credential claim.
--
-- The connection string is produced during asynchronous provisioning, long
-- after the HTTP request that asked for it has returned. It has to survive
-- until the requester comes back for it, without becoming something a database
-- dump hands to an attacker.
--
-- So the password is stored encrypted under a key that lives in the secret
-- manager rather than in this database, and the ciphertext is cleared the
-- moment it is claimed. A dump is useless without the key; a key leak is
-- useless against already-claimed leases. Encrypting rather than caching in
-- memory is what makes the claim work when the task that answers it is not the
-- task that provisioned the database.
-- ---------------------------------------------------------------------------

ALTER TABLE lease
    ADD COLUMN credential_ciphertext TEXT,
    ADD COLUMN credential_claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN lease.credential_ciphertext IS
    'AES-GCM ciphertext of the connection password, cleared on first claim. Null once claimed.';
COMMENT ON COLUMN lease.credential_claimed_at IS
    'When the connection string was handed out. A second claim is refused.';
