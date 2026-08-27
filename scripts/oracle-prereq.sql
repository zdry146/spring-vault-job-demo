-- ============================================================================
-- oracle-prereq.sql
-- ----------------------------------------------------------------------------
-- One-shot SQL to run on the target Oracle instance BEFORE vault-oracle-setup.sh.
--
-- Idempotent: re-running is safe (objects are dropped/recreated only when
-- the named user already exists).
--
-- USAGE (as a DBA, e.g. SYS or SYSTEM):
--   sqlplus sys/<sys-password>@//oracle-prod.local:1521/ORCLPDB1 as sysdba \
--     @scripts/oracle-prereq.sql
--
-- After running, set ORACLE_USER=vault_admin and ORACLE_PASSWORD=*** in the
-- shell that will run vault-oracle-setup.sh.
--
-- What this script does (mapped to the pitfalls list):
--   #3   dedicated vault_admin user, NOT SYS/SYSTEM
--   #10  dedicated app_profile (not DEFAULT) — verify_function off
--   #2   CONTAINER = CURRENT — only creates the user in this PDB,
--        not CDB$ROOT (avoids ORA-65096)
-- ============================================================================

-- --- 0. Pre-flight: confirm we're in a PDB, not CDB$ROOT ------------------
SET SERVEROUTPUT ON
DECLARE
  v_con_name VARCHAR2(128);
BEGIN
  SELECT SYS_CONTEXT('USERENV', 'CON_NAME') INTO v_con_name FROM DUAL;
  IF v_con_name = 'CDB$ROOT' THEN
    RAISE_APPLICATION_ERROR(
      -20001,
      'Connected to CDB$ROOT. Reconnect using the PDB service name ' ||
      '(e.g. ORCLPDB1) and re-run. See pitfall #2.'
    );
  END IF;
  DBMS_OUTPUT.PUT_LINE('OK: connected to PDB = ' || v_con_name);
END;
/

-- --- 1. vault_admin user (dedicated, NOT SYS/SYSTEM) ----------------------
-- Drop+recreate so the script is idempotent.
DECLARE
  v_cnt INTEGER;
BEGIN
  SELECT COUNT(*) INTO v_cnt FROM dba_users WHERE username = 'VAULT_ADMIN';
  IF v_cnt > 0 THEN
    EXECUTE IMMEDIATE 'DROP USER vault_admin CASCADE';
    DBMS_OUTPUT.PUT_LINE('    dropped existing vault_admin');
  END IF;
END;
/

-- Container = CURRENT pins creation to this PDB (#2).
-- Password placeholder '***' — set via the env var the shell expects:
--   ALTER USER vault_admin IDENTIFIED BY <your-real-password>;
CREATE USER vault_admin
  IDENTIFIED BY ***
  DEFAULT TABLESPACE users
  QUOTA UNLIMITED ON users
  PROFILE app_profile
  ACCOUNT UNLOCK
  CONTAINER = CURRENT;
-- ^^^  QUOTA UNLIMITED is required: Vault runs CREATE USER ... QUOTA ... on
--     its dynamic users; without QUOTA on USERS here, the dynamic users
--     would land in SYSTEM and trigger ORA-01950 (#9).

-- Minimum privileges to manage dynamic users and grant the typical
-- app-level rights. NO DBA role — that's the whole point of #23.
GRANT CREATE SESSION     TO vault_admin CONTAINER = CURRENT;
GRANT CREATE USER        TO vault_admin CONTAINER = CURRENT;
GRANT DROP USER          TO vault_admin CONTAINER = CURRENT;
GRANT ALTER USER         TO vault_admin CONTAINER = CURRENT;
GRANT GRANT ANY PRIVILEGE TO vault_admin CONTAINER = CURRENT;
GRANT GRANT ANY ROLE      TO vault_admin CONTAINER = CURRENT;
GRANT SELECT ANY DICTIONARY TO vault_admin CONTAINER = CURRENT;
-- ^^^ SELECT ANY DICTIONARY is needed for the revocation_statements
--     PL/SQL block to query v$session to kill lingering sessions (#12).

-- --- 2. app_profile (replaces Oracle's DEFAULT for our users) ------------
-- Avoids verify_function_11G rejecting Vault's random passwords (#10).
-- SESSIONS_PER_USER caps how many sessions any one dynamic user can hold,
-- protecting PROCESSES / SESSIONS limits when Vault issues high-frequency
-- leases (#4).
DECLARE
  v_cnt INTEGER;
BEGIN
  SELECT COUNT(*) INTO v_cnt FROM dba_profiles WHERE profile = 'APP_PROFILE';
  IF v_cnt > 0 THEN
    EXECUTE IMMEDIATE 'DROP PROFILE app_profile CASCADE';
    DBMS_OUTPUT.PUT_LINE('    dropped existing app_profile');
  END IF;
END;
/

CREATE PROFILE app_profile LIMIT
  COMPOSITE_LIMIT              UNLIMITED
  SESSIONS_PER_USER            5                -- (#4) protect Oracle PROCESSES
  CPU_PER_SESSION              UNLIMITED
  CPU_PER_CALL                 UNLIMITED
  LOGICAL_READS_PER_SESSION    UNLIMITED
  LOGICAL_READS_PER_CALL       UNLIMITED
  IDLE_TIME                    30               -- minutes; idle dynamic sessions recycled
  CONNECT_TIME                 UNLIMITED
  PRIVATE_SGA                  UNLIMITED
  FAILED_LOGIN_ATTEMPTS        UNLIMITED        -- Vault retries must NOT lock accounts
  PASSWORD_LIFE_TIME           UNLIMITED        -- Vault controls TTL, not Oracle
  PASSWORD_REUSE_TIME          UNLIMITED
  PASSWORD_REUSE_MAX           UNLIMITED
  PASSWORD_VERIFY_FUNCTION     NULL             -- (#10) random passwords must pass
  PASSWORD_LOCK_TIME           UNLIMITED
  PASSWORD_GRACE_TIME          UNLIMITED;

-- Apply the profile to vault_admin itself (so its own sessions honor the same
-- limits when it manages dynamic users).
ALTER USER vault_admin PROFILE app_profile;

-- --- 3. Sanity checks (run automatically at the end) ---------------------
SET LINESIZE 200
SET PAGESIZE 100
COLUMN grantee       FORMAT A20
COLUMN privilege     FORMAT A30
COLUMN profile       FORMAT A20
COLUMN limit         FORMAT A30

PROMPT
PROMPT ===================================================================
PROMPT  Sanity checks — verify vault_admin is correctly provisioned
PROMPT ===================================================================

PROMPT
PROMPT [1] vault_admin privileges (should NOT include DBA):
SELECT grantee, privilege
  FROM dba_sys_privs
WHERE grantee = 'VAULT_ADMIN'
ORDER BY privilege;

PROMPT
PROMPT [2] vault_admin tablespace quota:
SELECT username, tablespace_name, bytes, max_bytes
  FROM dba_ts_quotas
WHERE username = 'VAULT_ADMIN';

PROMPT
PROMPT [3] app_profile critical limits (should match the values above):
SELECT profile, resource_name, limit
  FROM dba_profiles
WHERE profile = 'APP_PROFILE'
  AND resource_name IN
      ('SESSIONS_PER_USER',
       'FAILED_LOGIN_ATTEMPTS',
       'PASSWORD_LIFE_TIME',
       'PASSWORD_VERIFY_FUNCTION',
       'IDLE_TIME')
ORDER BY resource_name;

PROMPT
PROMPT ===================================================================
PROMPT  Done. Next step: run vault-oracle-setup.sh with ORACLE_PASSWORD
PROMPT  set to the password you assigned to vault_admin.
PROMPT ===================================================================
-- Reminder: change the placeholder password on vault_admin BEFORE running
# vault-oracle-setup.sh:
#   sqlplus sys/<sys-pw>@.../ORCLPDB1 as sysdba
#   SQL> ALTER USER vault_admin IDENTIFIED BY "<real-password>" REPLACE ***;
-- (the REPLACE clause is a no-op stub; Vault expects to authenticate with
#  whatever password you set here)