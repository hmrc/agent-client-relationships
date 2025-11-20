
# Analysis of MongoDB Collections in agent-client-relationships

This document provides an analysis of the MongoDB collections used by the `agent-client-relationships` microservice. The information is derived from the repository source code in the `app/uk/gov/hmrc/agentclientrelationships/repository/` directory.

---

## 1. `invitations`

- **Repository File:** `InvitationsRepository.scala`
- **Purpose:** This is the primary collection for managing the agent-client invitation workflow. It stores records of invitations sent by agents to clients, allowing the service to track the status of an invitation from creation to acceptance or expiration.
- **Schema Highlights:**
  - `invitationId`: A unique identifier for the invitation.
  - `arn`: The Agent Reference Number of the agent who created the invitation.
  - `clientId`: The client's identifier (e.g., NINO, UTR) - encrypted in storage.
  - `clientIdType`: The type of client identifier (e.g., "ni", "utr").
  - `suppliedClientId`: The original client ID as supplied by the agent - encrypted in storage.
  - `suppliedClientIdType`: The type of the supplied client identifier.
  - `service`: The HMRC service the invitation pertains to (e.g., `HMRC-MTD-IT`, `HMRC-MTD-VAT`).
  - `clientName`: The name of the client - encrypted in storage.
  - `agencyName`: The name of the agent's agency - encrypted in storage.
  - `agencyEmail`: The agent's email address - encrypted in storage.
  - `status`: The current state of the invitation (e.g., `Pending`, `Accepted`, `Rejected`, `Expired`, `PartialAuth`).
  - `relationshipEndedBy`: Who ended the relationship (if applicable).
  - `clientType`: The type of client (optional).
  - `created`: Timestamp of when the invitation was created.
  - `lastUpdated`: Timestamp of the last status change.
  - `expiryDate`: The date when the invitation will automatically expire.
  - `warningEmailSent`: Boolean indicating if a warning email has been sent.
  - `expiredEmailSent`: Boolean indicating if an expiry email has been sent.

- **Sample Document:**

  ```json
  {
    "invitationId": "B944641C423A42528B89E43A516E3132",
    "arn": "TARN0000001",
    "clientId": "[ENCRYPTED]",
    "clientIdType": "ni",
    "suppliedClientId": "[ENCRYPTED]",
    "suppliedClientIdType": "ni",
    "service": "HMRC-MTD-IT",
    "clientName": "[ENCRYPTED]",
    "agencyName": "[ENCRYPTED]",
    "agencyEmail": "[ENCRYPTED]",
    "warningEmailSent": false,
    "expiredEmailSent": false,
    "status": "Pending",
    "relationshipEndedBy": null,
    "clientType": "personal",
    "created": { "$date": "2025-09-11T10:00:00.000Z" },
    "lastUpdated": { "$date": "2025-09-11T10:00:00.000Z" },
    "expiryDate": "2025-10-02"
  }
  ```

---

## 2. `partial-auth`

- **Repository File:** `PartialAuthRepository.scala`
- **Purpose:** This collection stores temporary relationship records during the ITSA (Income Tax Self Assessment) signup process. It tracks partial authorizations between agents and clients before they are converted to full relationships when the client completes their MTD-IT signup.
- **Schema Highlights:**
  - `created`: Timestamp of when the partial auth record was created.
  - `arn`: The Agent Reference Number.
  - `service`: The HMRC service (typically `HMRC-MTD-IT` or `HMRC-MTD-IT-SUPP`).
  - `nino`: The client's National Insurance Number - encrypted in storage.
  - `active`: Boolean indicating if the partial auth is currently active.
  - `lastUpdated`: Timestamp of the last update to this record.

- **Sample Document:**

  ```json
  {
    "created": { "$date": "2025-09-11T10:00:00.000Z" },
    "arn": "TARN0000001",
    "service": "HMRC-MTD-IT",
    "nino": "[ENCRYPTED]",
    "active": true,
    "lastUpdated": { "$date": "2025-09-11T10:00:00.000Z" }
  }
  ```

---

## 3. `agent-reference`

- **Repository File:** `AgentReferenceRepository.scala`
- **Purpose:** This collection stores a mapping between an agent's `uid` (Government Gateway user identifier) and their `arn` (Agent Reference Number), along with normalized agent names for invitation link validation.
- **Schema Highlights:**
  - `uid`: The agent's Government Gateway unique identifier.
  - `arn`: The agent's Agent Reference Number.
  - `normalisedAgentNames`: Array of normalized versions of the agent's name - encrypted in storage.

- **Sample Document:**

  ```json
  {
    "uid": "some-unique-gateway-id",
    "arn": "TARN0000001",
    "normalisedAgentNames": ["[ENCRYPTED]", "[ENCRYPTED]"]
  }
  ```

---

## 4. `relationship-copy-record`

- **Repository File:** `RelationshipCopyRecordRepository.scala`
- **Purpose:** This collection tracks the progress of copying client relationships from legacy HMRC systems to the modernized enrolment-store-proxy (ES) and ETMP systems. It's part of the migration strategy to the new agent services platform.
- **Schema Highlights:**
  - `arn`: The Agent Reference Number.
  - `enrolmentKey`: The enrolment key identifying the client's service enrollment.
  - `references`: Optional set of relationship references.
  - `dateTime`: Timestamp of when the copy record was created.
  - `syncToETMPStatus`: Status of synchronization to ETMP system (`Success`, `Failed`, `InProgress`).
  - `syncToESStatus`: Status of synchronization to Enrolment Store (`Success`, `Failed`, `InProgress`).

- **Sample Document:**

  ```json
  {
    "arn": "TARN0000001",
    "enrolmentKey": "HMRC-MTD-VAT~VRN~101747641",
    "references": [
      { "credId": "cred-123", "timestamp": { "$date": "2025-09-11T10:00:00.000Z" } }
    ],
    "dateTime": { "$date": "2025-09-11T10:00:00.000Z" },
    "syncToETMPStatus": "Success",
    "syncToESStatus": "Success"
  }
  ```

---

## 5. `delete-record`

- **Repository File:** `DeleteRecordRepository.scala`
- **Purpose:** This collection tracks requests to remove or 'de-authorize' agent-client relationships. When a relationship is terminated, a record is stored here to manage the cleanup of corresponding enrolments in downstream systems and track recovery attempts.
- **Schema Highlights:**
  - `arn`: The Agent Reference Number.
  - `enrolmentKey`: The enrolment key identifying the service enrollment to be deleted.
  - `dateTime`: Timestamp of when the delete record was created.
  - `syncToETMPStatus`: Status of deletion synchronization with ETMP system.
  - `syncToESStatus`: Status of deletion synchronization with Enrolment Store.
  - `lastRecoveryAttempt`: Timestamp of the last recovery attempt (if any).
  - `numberOfAttempts`: Number of recovery attempts made.
  - `headerCarrier`: Optional HTTP headers for request context.
  - `relationshipEndedBy`: Who initiated the relationship termination.

- **Sample Document:**

  ```json
  {
    "arn": "TARN0000001",
    "enrolmentKey": "HMRC-MTD-IT~MTDITID~XXIT00000000001",
    "dateTime": { "$date": "2025-09-11T11:00:00.000Z" },
    "syncToETMPStatus": "InProgress",
    "syncToESStatus": "Success",
    "lastRecoveryAttempt": { "$date": "2025-09-11T12:00:00.000Z" },
    "numberOfAttempts": 1,
    "headerCarrier": {
      "sessionId": "session-123",
      "authorization": "Bearer token-456"
    },
    "relationshipEndedBy": "Agent"
  }
  ```

---

## 6. `recovery-schedule`

- **Repository File:** `RecoveryScheduleRepository.scala`
- **Purpose:** This collection manages scheduled recovery tasks for the relationship copy and deletion operations. It provides a simple scheduling mechanism to retry failed operations at specified times, making the system resilient to temporary failures.
- **Schema Highlights:**
  - `uid`: A unique identifier for the recovery record (UUID).
  - `runAt`: The timestamp when the next recovery operation should be executed.

- **Sample Document:**

  ```json
  {
    "uid": "550e8400-e29b-41d4-a716-446655440000",
    "runAt": { "$date": "2025-09-11T12:00:00.000Z" }
  }
  ```

---

## 7. `locks`

- **Repository File:** `MongoLockRepositoryWithMdc.scala` (extends `MongoLockRepository` from hmrc-mongo library)
- **Purpose:** This collection provides distributed locking mechanism for scheduled jobs and background processes. It ensures that only one instance of a job runs at a time across multiple application instances. Used by the recovery scheduler and other background jobs to prevent concurrent execution.
- **Schema Highlights:**
  - `_id`: The lock identifier (unique).
  - `owner`: The identifier of the process/instance that owns the lock.
  - `expiryTime`: Timestamp when the lock will automatically expire.
  - `timeCreated`: Timestamp when the lock was created.

- **Sample Document:**

  ```json
  {
    "_id": "RecoveryJob",
    "owner": "instance-1",
    "expiryTime": { "$date": "2025-09-11T12:30:00.000Z" },
    "timeCreated": { "$date": "2025-09-11T12:00:00.000Z" }
  }
  ```

- **Notes:**
  - Provided by the `uk.gov.hmrc.mongo.lock.MongoLockRepository` library.
  - `MongoLockRepositoryWithMdc` wraps the standard lock repository to preserve MDC (Mapped Diagnostic Context) for logging.
  - Locks have a TTL (Time To Live) to prevent stuck locks from blocking jobs indefinitely.
  - Used for distributed coordination across multiple application instances.

---

## Summary and Key Concepts

### Encryption

Several collections contain sensitive personal data that is encrypted at rest using AES encryption:

- **`invitations`**: `clientId`, `suppliedClientId`, `clientName`, `agencyName`, `agencyEmail`
- **`partial-auth`**: `nino`
- **`agent-reference`**: `normalisedAgentNames`

The encryption is managed by the `@Named("aes") crypto: Encrypter with Decrypter` dependency injected into each repository. The encrypted fields are stored as encrypted strings but are transparently encrypted/decrypted by the repository layer.

### Synchronization Status Values

The `relationship-copy-record` and `delete-record` collections use `syncToETMPStatus` and `syncToESStatus` fields with the following possible values:

- **`Success`**: Operation completed successfully
- **`Failed`**: Operation failed and needs recovery
- **`InProgress`**: Operation is currently being processed

### Relationship Lifecycle

1. **Invitation Created** → Record in `invitations` with status `Pending`
2. **Client Accepts** → Status changes to `Accepted`, may create `partial-auth` record for ITSA
3. **Relationship Created** → Record in `relationship-copy-record` to track sync to EACD/ETMP
4. **Relationship Ended** → Record in `delete-record` to track cleanup in EACD/ETMP
5. **Recovery** → Failed operations scheduled in `recovery-schedule` for retry

### Collection Relationships

- **`invitations`** ↔ **`agent-reference`**: Invitations reference agent UIDs from agent-reference for external API
- **`partial-auth`** ↔ **`invitations`**: Partial auth created when invitation accepted for ITSA signup
- **`relationship-copy-record`** ↔ **`delete-record`**: Mirror collections for create/delete operations
- **`recovery-schedule`** ↔ **`relationship-copy-record`/`delete-record`**: Recovery tasks for failed sync operations
- **`locks`**: Ensures only one recovery job runs at a time across instances

### Indexes and Performance

- **Unique indexes**: `invitations.invitationId`, `agent-reference.uid`, `agent-reference.arn`, `partial-auth` (on service+nino+arn when active)
- **Compound indexes**: `partial-auth` has compound index on (service, nino, arn, active) for efficient relationship queries
- **TTL indexes**: Most collections use TTL indexes except `agent-reference` (permanent records) and `locks` (library-managed TTL)

### API Endpoint Usage

- **Internal APIs (ACR01-ACR30)**: Primarily use `invitations`, `partial-auth`, `relationship-copy-record`, `delete-record`
- **External APIs (ACR31-ACR34)**: Use `agent-reference` for stable agent identifiers (UID) and `invitations` for invitation management
- **Background Jobs**: Use `locks` for coordination, `recovery-schedule` for retry logic, and update `relationship-copy-record`/`delete-record` statuses

---

## Additional Notes

- All repositories use `Mdc.preservingMdc` to maintain Mapped Diagnostic Context for logging across async boundaries
- The service uses `uk.gov.hmrc.mongo.play.json.PlayMongoRepository` from the hmrc-mongo library
- Encryption uses `uk.gov.hmrc.agentclientrelationships.util.CryptoUtil.encryptedString` helper
- Most operations are asynchronous returning `Future[T]`
- The system is designed for eventual consistency with recovery mechanisms for failed operations

---

**Last Updated:** November 2025  
**MongoDB Driver:** org.mongodb.scala (Reactive Streams)  
**Play Framework Version:** 2.9.x  
**Scala Version:** 2.13.x


