
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
    "status": "Pending",
    "created": { "$date": "2025-09-11T10:00:00.000Z" },
    "lastUpdated": { "$date": "2025-09-11T10:00:00.000Z" },
    "expiryDate": { "$date": "2025-10-02T10:00:00.000Z" },
    "events": [
      {
        "time": { "$date": "2025-09-11T10:00:00.000Z" },
        "status": "Pending"
      }
    ]
  }
  ```

---

## 2. `partial-auth`

- **Repository File:** `PartialAuthRepository.scala`
- **Purpose:** This collection is used to temporarily store information during the "authorisation" process, where a client is attempting to accept an agent's invitation but may need to complete additional authentication steps. It holds the state while the client is redirected to other services (like Government Gateway) to confirm their identity.
- **Schema Highlights:**
  - `credId`: The user's credential ID from Government Gateway.
  - `clientIds`: A list of client identifiers associated with the user's credentials.
  - `redirectUrl`: The URL to redirect the user back to after they complete the external authentication steps.

- **Sample Document:**

  ```json
  {
    "credId": "1234567890123456",
    "clientIds": ["AB123456A", "CD789012B"],
    "redirectUrl": "/agent-client-relationships/some-continue-url"
  }
  ```

---

## 3. `agent-reference`

- **Repository File:** `AgentReferenceRepository.scala`
- **Purpose:** This collection stores a mapping between an agent's `arn` (Agent Reference Number) and their `uid` (a unique identifier, likely related to their Government Gateway account). This allows the service to look up agent details using either identifier.
- **Schema Highlights:**
  - `uid`: The agent's unique identifier.
  - `arn`: The agent's Agent Reference Number.

- **Sample Document:**

  ```json
  {
    "uid": "some-unique-gateway-id",
    "arn": "TARN0000001"
  }
  ```

---

## 4. `relationship-copy-record`

- **Repository File:** `RelationshipCopyRecordRepository.scala`
- **Purpose:** This collection is used to track the progress of copying client relationships from legacy HMRC systems (like `agent-mapping`) to the modernised `enrolment-store-proxy`. This is a crucial part of the migration strategy to the new agent services platform.
- **Schema Highlights:**
  - `arn`: The Agent Reference Number.
  - `service`: The HMRC service for which relationships are being copied.
  - `clientIdentifier`: The identifier of the client.
  - `clientIdentifierType`: The type of the client identifier.
  - `syncToESStatus`: The status of the synchronisation to the `enrolment-store-proxy` (e.g., `InProgress`, `Success`, `Failed`).

- **Sample Document:**

  ```json
  {
    "arn": "TARN0000001",
    "service": "HMRC-MTD-VAT",
    "clientIdentifier": "101747641",
    "clientIdentifierType": "vrn",
    "syncToESStatus": "Success"
  }
  ```

---

## 5. `delete-record`

- **Repository File:** `DeleteRecordRepository.scala`
- **Purpose:** This collection tracks requests to remove or "de-authorise" agent-client relationships. When a relationship is terminated, a record is stored here to keep a log of these events and potentially to manage the cleanup of corresponding enrolments in downstream systems.
- **Schema Highlights:**
  - `arn`: The Agent Reference Number.
  - `service`: The relevant HMRC service.
  - `clientIdentifier`: The client's identifier.
  - `clientIdentifierType`: The type of the client identifier.
  - `syncToESStatus`: The status of the de-authorisation synchronisation with the `enrolment-store-proxy`.
  - `lastFound`: Timestamp of the last time the record was processed.

- **Sample Document:**

  ```json
  {
    "arn": "TARN0000001",
    "service": "HMRC-MTD-IT",
    "clientIdentifier": "AB123456A",
    "clientIdentifierType": "ni",
    "syncToESStatus": "InProgress",
    "lastFound": { "$date": "2025-09-11T11:00:00.000Z" }
  }
  ```

---

## 6. `recovery-schedule`

- **Repository File:** `RecoveryScheduleRepository.scala`
- **Purpose:** This collection is used for managing scheduled, asynchronous recovery tasks. If an operation (like creating a relationship in a downstream service) fails, a record can be created here to retry the operation later. This makes the system more resilient to temporary failures in other microservices.
- **Schema Highlights:**
  - `arn`: The Agent Reference Number.
  - `service`: The HMRC service.
  - `clientIdentifier`: The client's identifier.
  - `clientIdentifierType`: The type of the client identifier.
  - `action`: The action to be retried (e.g., `create-relationship`).
  - `tryCount`: The number of times the recovery has been attempted.
  - `nextTryTime`: The timestamp for the next scheduled retry attempt.

- **Sample Document:**

  ```json
  {
    "arn": "TARN0000001",
    "service": "HMRC-MTD-IT",
    "clientIdentifier": "AB123456A",
    "clientIdentifierType": "ni",
    "action": "create-relationship",
    "tryCount": 2,
    "nextTryTime": { "$date": "2025-09-11T12:00:00.000Z" }
  }
  ```
