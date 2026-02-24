# Agent Client Relationships System Analysis

**Services:** agent-client-relationships (backend) + agent-client-relationships-frontend  
**Domain:** Agent-Client Authorization & Relationship Management  
**Date:** 14 January 2026

---

## Executive Summary

The Agent Client Relationships system is a tightly coupled frontend-backend pair that manages the complete lifecycle of authorization requests (invitations) between tax agents and their clients. It handles creating, accepting, rejecting, and terminating relationships for multiple tax services including MTD-IT, MTD-VAT, Trusts, CGT, PPT, CBC, and Pillar 2.

**Key Capabilities:**

- Agent creates authorization requests (invitations) for clients
- Clients accept or reject invitations via unique URLs
- Dual-track relationship creation (ETMP + Enrolment Store)
- Agent and client can terminate relationships
- Tracking dashboard for agents to monitor pending/active/rejected requests
- Support for complex scenarios (Alternative ITSA, agent replacement, partial auth)

---

## 1. System Architecture

### 1.1 Service Coupling

**Backend:** `agent-client-relationships`

- RESTful API service
- MongoDB for persistence (invitations, relationship sync records)
- Integrates with ETMP, Enrolment Store, Agent Assurance, Agent Mapping
- Manages invitation lifecycle and relationship state

**Frontend:** `agent-client-relationships-frontend`

- Play Framework web application
- Provides UI for both agents and clients
- Handles authentication/authorization via auth-client
- Orchestrates backend API calls
- Session management for journey state

**Integration:** The frontend exclusively communicates with this backend service via HTTP REST APIs. No direct database access from frontend.

### 1.2 Key External Dependencies

```
┌─────────────────────────────────────────────────────────────┐
│                    Frontend (Port 9435)                     │
│  - Agent journeys (create, track, cancel)                   │
│  - Client journeys (accept, reject, view agents)            │
│  - Fast-track invitation creation                           │
└──────────────────────┬──────────────────────────────────────┘
                       │ HTTP REST
┌──────────────────────▼──────────────────────────────────────┐
│                  Backend (Port 9434)                        │
│  - Invitation management (CRUD)                             │
│  - Relationship orchestration                                │
│  - Integration layer                                        │
└┬────────┬────────┬──────────┬──────────┬────────────┬───────┘
 │        │        │          │          │            │
 │ ETMP   │ ES     │ Agent    │ Agent    │ Citizen    │ MongoDB
 │ (tax   │ (auth  │ Assurance│ Mapping  │ Details    │ (state)
 │ data)  │ enrol) │ (checks) │ (ARN)    │ (names)    │
```

---

## 2. Core Domain Model

### 2.1 Invitation

The central entity representing an authorization request from agent to client.

**Key Attributes:**

| Field | Type | Description |
|-------|------|-------------|
| `invitationId` | String | Unique identifier (derived from ARN + clientId + service) |
| `arn` | String | Agent Reference Number |
| `service` | Service enum | Tax service (e.g., HMRC-MTD-IT, HMRC-MTD-VAT) |
| `clientId` | TaxIdentifier | Actual client ID (may be resolved, e.g., MTDITID) |
| `suppliedClientId` | TaxIdentifier | Client ID as provided by agent (e.g., NINO) |
| `clientName` | String | Client's name (encrypted) |
| `agencyName` | String | Agent's trading name (encrypted) |
| `agencyEmail` | String | Agent's contact email (encrypted) |
| `status` | InvitationStatus | Current state (see lifecycle) |
| `expiryDate` | LocalDate | When invitation expires (typically +21 days) |
| `relationshipEndedBy` | Option[String] | Who terminated (Agent/Client/HMRC) |
| `clientType` | Option[String] | Client classification (personal/business/trust) |
| `warningEmailSent` | Boolean | Pre-expiry warning email sent |
| `expiredEmailSent` | Boolean | Post-expiry notification sent |
| `created` | Instant | Creation timestamp |
| `lastUpdated` | Instant | Last modification timestamp |

**Data Protection:**

- Sensitive fields (`clientId`, `clientName`, `agencyName`, `agencyEmail`) encrypted at rest using AES
- Encryption keys managed via `uk.gov.hmrc.crypto`

### 2.2 Invitation Status Lifecycle

```
         ┌─────────┐
         │ Pending │  (Initial state - awaiting client response)
         └────┬────┘
              │
       ┌──────┴───────┬────────────┬───────────┐
       │              │            │           │
       ▼              ▼            ▼           ▼
  ┌─────────┐   ┌──────────┐  ┌──────────┐  ┌─────────┐
  │Accepted │   │ Rejected │  │Cancelled │  │ Expired │
  └────┬────┘   └──────────┘  └──────────┘  └─────────┘
       │
       │ (or)
       ▼
  ┌────────────┐
  │PartialAuth │  (Alt ITSA only - client not MTD enrolled)
  └──────┬─────┘
         │
         │ (relationship terminated)
         ▼
    ┌──────────────┐
    │ DeAuthorised │  (Terminal state)
    └──────────────┘
```

**Status Definitions:**

1. **Pending**: Invitation created, awaiting client action (initial state)
2. **Accepted**: Client accepted, full relationship established
3. **PartialAuth**: Alt ITSA partial authorization (client not MTD-registered)
4. **Rejected**: Client declined the invitation
5. **Cancelled**: Agent cancelled before client responded
6. **Expired**: Invitation passed expiry date without response
7. **DeAuthorised**: Relationship terminated after acceptance (terminal state)

**Business Rules:**

- Only one `Pending` invitation allowed per (ARN, service, suppliedClientId)
- `Accepted` or `PartialAuth` invitations can transition to `DeAuthorised`
- Terminal states: `Rejected`, `Cancelled`, `Expired`, `DeAuthorised`
- Accepting new invitation auto-deauthorizes previous agent for same service

---

## 3. Key Workflows

### 3.1 Create Invitation (Agent-Initiated)

**User Story:** Agent wants to request authorization to manage client's tax affairs for a specific service.

**Frontend Flow:**

1. **Journey Type Selection:** Create invitation OR Fast-track
2. **Select Client Type:** Personal, Business, or Trust
3. **Select Service:** MTD-IT, MTD-VAT, CGT, Trusts, etc.
4. **Service Refinement:** Main agent vs Supporting agent (ITSA only)
5. **Enter Client ID:** NINO, VRN, UTR, CGT ref, etc.
6. **Enter Known Fact:** Postcode, DOB, VAT registration date
7. **Confirm Client:** Display matched client name for verification
8. **Select Agent Role:** (ITSA only) Main or Supporting
9. **Check for Existing Relationship:** Confirm replacement if exists
10. **Check Your Answers:** Review and confirm
11. **Submit:** POST to backend `/agent/:arn/authorisation-request`
12. **Confirmation:** Display invitation ID and share link with client

**Backend Processing:**

```scala
POST /agent/:arn/authorisation-request
{
  "clientId": "AB123456C",
  "suppliedClientIdType": "ni",
  "clientName": "John Smith",
  "service": "HMRC-MTD-IT",
  "clientType": "personal"
}
```

**Backend Steps:**

1. **Validate Request:** Service, clientIdType, clientType combinations
2. **Fetch Agent Record:** Call agent-assurance to get agency details
3. **Resolve Client ID:**
   - For ITSA: NINO → MTDITID via HIP (IF-ITSA-INDIVIDUAL)
   - Other services: Use supplied ID directly
4. **Check Agent Assurance:**
   - For ITSA: Validate IRV (Identity Risk Verification) status
   - All: Check agent not suspended
5. **Check for Duplicates:** Query MongoDB for pending invitation
6. **Generate Invitation ID:** Hash(ARN + clientId + service)
7. **Create Invitation Record:**
   - Status = Pending
   - ExpiryDate = now + 21 days
   - Encrypt sensitive fields
   - Store in `invitations` collection
8. **Audit Event:** Log invitation created
9. **Return:** 201 Created with `{"invitationId": "ABC..."}`

**MongoDB Unique Index:**

```scala
Index on (arn, service, suppliedClientId) 
WHERE status = Pending
```

Prevents duplicate pending invitations.

**Error Scenarios:**

- `DuplicateInvitationError` (403): Pending invitation already exists
- `UnsupportedService` (400): Invalid service key
- `InvalidClientId` (400): Client ID validation failed
- `ClientRegistrationNotFound` (403): Client not registered for service
- `UnsupportedClientIdType` (400): Wrong ID type for service

**Fast-Track Variant:**
Frontend can POST directly to `/agents/fast-track` with all data pre-populated (bypasses UI journey).

---

### 3.2 Accept Invitation (Client-Initiated)

**User Story:** Client receives invitation link and wants to authorize agent.

**Frontend Flow:**

1. **Access Unique URL:** `/:uid/:normalizedAgentName/:taxService`
2. **Validate Link:** Verify UID maps to valid pending invitation
3. **Display Invitation:** Show agent name, service, client details
4. **Consent Information:** Explain what agent will access
5. **Confirm Consent:** Client agrees to authorize
6. **Check Your Answers:** Review details
7. **Submit:** PUT to backend `/authorisation-response/accept/:invitationId`
8. **Processing:** Show spinner while backend creates relationship
9. **Confirmation:** Success message

**Backend Processing:**

```scala
PUT /authorisation-response/accept/:invitationId
```

**Backend Steps:**

1. **Validate Invitation:**
   - Find by invitationId
   - Check status = Pending
   - Verify not expired
2. **Authenticate Client:**
   - Extract client identifier from auth enrolment
   - Verify matches invitation's clientId
   - OR: Allow Stride user with `maintain_agent_relationships` role
3. **Handle Special Cases:**
   - **Alt ITSA:** Client NINO matches but no MTDITID → Create PartialAuth
   - **ITSA Main/Supp:** Remove same-agent relationship for alternate service
   - **Standard:** Proceed to relationship creation
4. **Create Relationship** (Dual-Track, with MongoDB Lock):

   **Lock:** `(arn, enrolmentKey)` to prevent concurrent modifications

   **Track A - ETMP (Tax Backend):**
   - Create `RelationshipCopyRecord` in MongoDB
   - POST to HIP `/registration/relationship` or `/income-tax-self-assessment/person/:clientId/agent/:arn`
   - Update sync status: `syncToETMPStatus = Success`

   **Track B - Enrolment Store (Auth Backend):**
   - Resolve agent's group ID from `groups-search` service
   - POST to `/tax-enrolments/groups/:groupId/enrolments/:enrolmentKey`
   - Update sync status: `syncToESStatus = Success`

   **Complete:** Remove `RelationshipCopyRecord`

5. **Update Invitation Status:** Pending → Accepted (or PartialAuth for Alt ITSA)
6. **Deauthorize Previous Relationships:**
   - Query for other Accepted invitations (same client, same service)
   - Mark as DeAuthorised
   - Set `relationshipEndedBy = Client`
   - For Alt ITSA: Handle partial auth deauthorization
7. **Send Acceptance Email:** Notify agent of client acceptance
8. **Update Friendly Name:** Call ES to set agent name for client enrolment
9. **Cache Refresh:** Trigger agent-user-client-details cache update
10. **Audit Events:** Log acceptance and relationship creation
11. **Return:** 204 No Content (success)

**Error Scenarios:**

- `CreateRelationshipLocked` (423): Another process creating relationship (retry)
- `RelationshipNotFound` (500): No admin agent user found for relationship
- `NoPendingInvitation` (403): Invitation not found or not pending
- `InvalidAuth` (403): Client ID doesn't match invitation
- Partial failure: Stores state in `RelationshipCopyRecord`, resumes on retry

**Recovery Pattern:**
If process fails mid-way (e.g., ETMP succeeds but ES fails):

1. `RelationshipCopyRecord` persists with sync status flags
2. Next accept attempt detects existing record
3. Skips completed steps (ETMP), retries failed steps (ES)
4. Ensures eventual consistency

---

### 3.3 Reject Invitation (Client-Initiated)

**User Story:** Client receives invitation but wants to decline.

**Frontend Flow:**

1. **Access Invitation:** Same entry as accept flow
2. **Review Details:** Display agent, service
3. **Confirm Decline:** Client confirms rejection
4. **Submit:** PUT to backend `/client/authorisation-response/reject/:invitationId`
5. **Confirmation:** Display rejection confirmation

**Backend Processing:**

```scala
PUT /client/authorisation-response/reject/:invitationId
```

**Backend Steps:**

1. **Find Invitation:** Lookup by invitationId
2. **Validate Status:** Must be Pending
3. **Authenticate Client:** Verify client ID matches OR Stride user
4. **Update Status:** Pending → Rejected
5. **Send Rejection Email:** Notify agent
6. **Audit Event:** Log rejection
7. **Return:** 204 No Content

**Simplicity:** No relationship creation, just status update + notification.

**Error Scenarios:**

- `NoPendingInvitation` (403): Not found or wrong status
- `InvalidAuth` (403): Wrong client

---

### 3.4 Cancel Invitation (Agent-Initiated)

**User Story:** Agent wants to cancel a pending invitation before client responds.

**Frontend Flow:**

1. **Track Requests Dashboard:** View pending invitations
2. **Select Invitation:** Click cancel on specific invitation
3. **Confirm Cancellation:** Confirm action
4. **Submit:** PUT to backend `/agent/cancel-invitation/:invitationId`
5. **Confirmation:** Display cancellation confirmation

**Backend Processing:**

```scala
PUT /agent/cancel-invitation/:invitationId
```

**Backend Steps:**

1. **Find Invitation:** Lookup by invitationId and arn
2. **Verify Ownership:** ARN must match invitation
3. **Validate Status:** Must be Pending
4. **Update Status:** Pending → Cancelled
5. **Audit Event:** Log cancellation
6. **Return:** 204 No Content

**Error Scenarios:**

- `InvitationNotFound` (404): Doesn't exist
- `NoPermissionOnAgency` (403): Different agent's invitation
- `InvalidInvitationStatus` (403): Not in Pending state

---

### 3.5 Remove Authorisation / Deauthorize (Agent or Client)

**User Story:** Agent or client wants to terminate an existing relationship.

**Frontend Flow (Agent):**

1. **Agent Cancellation Journey:** Select service and client
2. **Confirm Deauthorisation:** Review and confirm
3. **Submit:** POST to backend `/agent/:arn/remove-authorisation`
4. **Processing:** Show spinner during relationship deletion
5. **Confirmation:** Display success

**Frontend Flow (Client):**

1. **Manage Your Tax Agents:** View active relationships
2. **Select Agent:** Choose agent to remove
3. **Confirm Removal:** Confirm action
4. **Submit:** Same backend endpoint
5. **Confirmation:** Display success

**Backend Processing:**

```scala
POST /agent/:arn/remove-authorisation
{
  "service": "HMRC-MTD-IT",
  "clientId": "XXMTDITIDXX"
}
```

**Backend Steps:**

1. **Validate Request:** Service, clientId provided
2. **Authenticate User:** Agent, Client, or Stride
3. **Determine Who Initiated:** Track in `relationshipEndedBy`
4. **Handle Special Cases:**
   - **PIR (Personal Income Record):** Call AFI-relationship connector
   - **Alt ITSA (PartialAuth):** Deauthorize partial auth only
   - **Standard:** Full relationship deletion
5. **Delete Relationship** (Dual-Track, with MongoDB Lock):

   **Lock:** `(arn, enrolmentKey)` to prevent concurrent operations

   **Create Delete Record:** Track deletion progress in MongoDB

   **Track A - Enrolment Store:**
   - DELETE `/tax-enrolments/groups/:groupId/enrolments/:enrolmentKey`
   - Update sync status: `syncToESStatus = Success`

   **Track B - ETMP:**
   - PUT HIP `/registration/relationship/end` or DELETE for other services
   - Update sync status: `syncToETMPStatus = Success`

   **Complete:** Remove DeleteRecord

6. **Update Invitation Status:** Accepted/PartialAuth → DeAuthorised
7. **Set Relationship Ended By:** Agent, Client, or HMRC
8. **Cache Refresh:** Trigger agent-user-client-details cache update
9. **Audit Events:** Log deauthorization
10. **Return:** 204 No Content

**Error Scenarios:**

- `RelationshipNotFound` (404): No relationship exists
- `DeleteRelationshipLocked` (423): Concurrent deletion attempt (retry)
- Partial failure: Stores state in `DeleteRecord`, resumes on retry

**Recovery Pattern:** Similar to relationship creation - stores progress, resumes from failure point.

---

### 3.6 Track Requests (Agent Dashboard)

**User Story:** Agent wants to view all pending, accepted, and recent invitations.

**Frontend Flow:**

1. **Dashboard:** `/manage-authorisation-requests`
2. **Display Table:** Paginated list of invitations
3. **Filters:** Status, Client Name
4. **Actions:** Resend, Cancel, View Details
5. **Pagination:** Navigate through results

**Backend Processing:**

```scala
GET /agent/:arn/authorisation-requests
  ?statusFilter=Pending,Accepted
  &clientName=John
  &pageNumber=1
  &pageSize=20
```

**Backend Steps:**

1. **Authenticate Agent:** Verify ARN
2. **Query MongoDB:** Invitations collection
   - Filter by ARN
   - Filter by status (if provided)
   - Fuzzy search on clientName (if provided)
   - Sort by lastUpdated DESC
   - Skip/limit for pagination
3. **Decrypt Fields:** clientName, agencyName
4. **Return:** Paginated result with total count

**Response Structure:**

```json
{
  "invitations": [
    {
      "invitationId": "ABC...",
      "service": "HMRC-MTD-IT",
      "clientName": "John Smith",
      "status": "Pending",
      "created": "2026-01-01T10:00:00Z",
      "expiryDate": "2026-01-22"
    }
  ],
  "totalResults": 50
}
```

---

### 3.7 Validate Invitation Link

**User Story:** Client clicks invitation URL; system verifies it's valid.

**Frontend Flow:**

1. **Client Accesses:** `/:uid/:normalizedAgentName/:taxService`
2. **Call Backend:** GET `/agent/agent-reference/uid/:uid/:normalizedAgentName`
3. **Display:** If valid, show invitation details; else, error page

**Backend Processing:**

```scala
GET /agent/agent-reference/uid/:uid/:normalizedAgentName
```

**Backend Steps:**

1. **Decode UID:** Base64 decode to get invitationId
2. **Find Invitation:** Query MongoDB
3. **Validate:**
   - Status = Pending
   - Not expired
   - Agent name matches (normalized comparison)
   - Agent not suspended
4. **Return:** Invitation details OR 404

**Error Scenarios:**

- `InvalidLink` (404): UID invalid or invitation not found
- `AgentSuspended` (403): Agent no longer authorized
- `InvitationExpired` (403): Past expiry date

---

## 4. Alternative ITSA (Partial Auth)

### 4.1 What is Alt ITSA?

**Scenario:** Client has a NINO but hasn't signed up for Making Tax Digital (MTD) for Income Tax. Agent still wants to manage their affairs.

**Solution:** Create a "partial authorization" relationship.

**Differences from Standard ITSA:**

1. **Status:** `PartialAuth` (not `Accepted`)
2. **Storage:** `PartialAuthRepository` (separate collection)
3. **Client ID:** NINO (not MTDITID)
4. **Relationship:** Only in ETMP, not in Enrolment Store
5. **Visibility:** Agent can see client in dashboard but with limited access

### 4.2 Alt ITSA Workflow

**Accept Invitation:**

1. Client has NINO enrolment
2. No MTDITID (not MTD enrolled)
3. Backend detects this condition
4. Creates PartialAuth instead of Accepted
5. Stores in `PartialAuthRepository`
6. Creates relationship in ETMP only (not ES)

**When Client Signs Up for MTD:**

1. Client enrolls for MTD → receives MTDITID
2. Agent can create new invitation with MTDITID
3. Accept creates full relationship (Accepted status)
4. Previous PartialAuth deauthorized

**Deauthorization:**

- Similar flow but only removes from ETMP
- Marks PartialAuth as DeAuthorised

---

## 5. Agent Access Groups Integration

### 5.1 User-Level Relationships

When agent access groups are enabled:

- Relationships checked at **agent user level** (not agency level)
- Client must be in same access group as agent user, OR
- Client not assigned to any access group (agency-wide access)

### 5.2 API Parameter

Many endpoints accept optional `userId` query parameter:

```scala
GET /agent/:arn/service/:service/client/:clientIdType/:clientId?userId=user123
```

If provided, checks relationship at user level; else, agency level.

---

## 6. Data Model Deep Dive

### 6.1 MongoDB Collections

**invitations:**

- Primary collection for all invitations
- Unique index: `(arn, service, suppliedClientId, status=Pending)`
- TTL index: Auto-delete old invitations after configured period
- Encrypted fields: `clientId`, `clientName`, `agencyName`, `agencyEmail`

**partialAuths:**

- Alt ITSA partial authorizations
- Similar structure to invitations
- Separate lifecycle

**relationshipCopyRecords:**

- Tracks relationship creation progress
- Fields: `arn`, `enrolmentKey`, `syncToETMPStatus`, `syncToESStatus`
- Enables recovery from partial failures
- Deleted once both sync operations succeed

**deleteRecords:**

- Tracks relationship deletion progress
- Similar to relationshipCopyRecords
- Enables recovery from partial failures

### 6.2 Encryption

Uses `SensitiveWrites` and `SensitiveReads` from `uk.gov.hmrc.crypto`:

```scala
implicit val crypto: Encrypter with Decrypter = secureGCMCipher
implicit val nF = NinoFormat
implicit val mtdF = MtdItIdFormat

case class Invitation(
  /**
   * Represents a tax identifier for a client in the agent-client relationship system.
   * 
   * @param clientId The unique tax identifier used to identify a client within HMRC systems.
   *                 This is marked as @Sensitive as it contains personally identifiable information
   *                 that must be handled according to data protection regulations.
   * 
   * @note This parameter contains sensitive personal data and should be:
   *       - Handled in accordance with GDPR and data protection policies
   *       - Never logged in plain text
   *       - Encrypted when stored or transmitted
   *       - Accessed only by authorized personnel/systems
   */
  @Sensitive clientId: TaxIdentifier,
  @Sensitive clientName: String,
  @Sensitive agencyName: String,
  @Sensitive agencyEmail: String,
  // ...
)
```

Automatically encrypts on write, decrypts on read.

---

## 7. External Service Integrations

### 7.1 ETMP (Enterprise Tax Management Platform)

**Purpose:** Tax backend - master record of agent-client relationships

**Operations:**

- **Create Relationship:** POST `/registration/relationship`
- **Delete Relationship:** PUT `/registration/relationship/end`
- **ITSA-specific:** Routes via HIP to IF APIs

**Called For:**

- All Accepted relationships
- Alt ITSA (PartialAuth)
- Deauthorizations

### 7.2 Enrolment Store (ES) / tax-enrolments

**Purpose:** Auth backend - delegated enrolments for agent access

**Operations:**

- **Allocate Enrolment:** POST `/tax-enrolments/groups/:groupId/enrolments/:enrolmentKey`
- **Deallocate Enrolment:** DELETE `/tax-enrolments/groups/:groupId/enrolments/:enrolmentKey`

**Called For:**

- All Accepted relationships (not PartialAuth)
- Deauthorizations

### 7.3 Agent Assurance

**Purpose:** Agent validation and compliance checks

**Operations:**

- **Get Agent Record:** GET agent details, trading name
- **IRV Check:** Validate Identity Risk Verification status (ITSA)
- **Client Limit Check:** Validate agent hasn't exceeded client count limits (API endpoints)

**Called For:**

- Every invitation creation
- IRV validation for ITSA invitations

### 7.4 Agent Mapping

**Purpose:** Validate ARN mappings for legacy systems

**Operations:**

- **Validate Mapping:** Ensure ARN has required mappings for service

**Called For:**

- Certain services that require legacy system mappings

### 7.5 Citizen Details

**Purpose:** Fetch citizen name for display

**Operations:**

- **Get Citizen:** Retrieve name from NINO

**Called For:**

- Client name resolution in some flows

### 7.6 Agent User Client Details

**Purpose:** Manages cached client lists for agent users

**Operations:**

- **Cache Refresh:** Trigger recalculation of agent's client list

**Called For:**

- After relationship creation/deletion
- Ensures agent sees updated client list immediately

---

## 8. Authentication & Authorization

### 8.1 Frontend Authentication

Uses `auth-client` library with:

- **Agent Auth:** Requires HMRC-AS-AGENT enrolment + ARN
- **Client Auth:** Requires service-specific enrolment (MTD-IT, MTD-VAT, etc.)
- **Stride Auth:** Internal HMRC users with specific roles

### 8.2 Backend Authorization

Uses `AuthActions` trait with methods:

- `authorisedAgent`: Validates ARN from auth enrolment
- `authorisedClient`: Validates client ID from auth enrolment
- `authorisedUser`: Allows agent, client, or Stride

### 8.3 Authorization Patterns

**Two-Phase Auth (for some endpoints):**

1. **Lookup:** Find invitation/relationship first
2. **Validate:** Check if authenticated user is authorized for that resource

Example (ACR15 - Reject Invitation):

```scala
def rejectInvitation(invitationId: String) = Action.async { implicit request =>
  // Phase 1: Lookup
  invitationsRepository.findOneById(invitationId).flatMap {
    case Some(invitation) if invitation.status == Pending =>
      // Phase 2: Authorize
      authorisedUser(invitation.clientId, invitation.service).flatMap { _ =>
        // Perform rejection
        invitationService.rejectInvitation(invitationId)
      }
    case _ => Future.successful(Forbidden)
  }
}
```

Prevents information leakage (doesn't reveal invitation existence unless authorized).

---

## 9. Error Handling & Recovery

### 9.1 Validation Errors (4xx)

| Error | Status | Scenario |
|-------|--------|----------|
| UnsupportedService | 400 | Invalid service key |
| InvalidClientId | 400 | Client ID format invalid |
| UnsupportedClientIdType | 400 | Wrong ID type for service |
| UnsupportedClientType | 400 | Invalid client type |
| DuplicateInvitationError | 403 | Pending invitation exists |
| ClientRegistrationNotFound | 403 | Client not registered |
| NoPendingInvitation | 403 | Invitation not found/not pending |
| InvalidAuth | 403 | User not authorized |
| InvitationNotFound | 404 | Invitation doesn't exist |
| RelationshipNotFound | 404 | Relationship doesn't exist |

### 9.2 Concurrency Control (423)

| Error | Status | Scenario |
|-------|--------|----------|
| CreateRelationshipLocked | 423 | Another process creating relationship |
| DeleteRelationshipLocked | 423 | Another process deleting relationship |

Frontend should retry after delay (exponential backoff).

### 9.3 Partial Failure Recovery

**Problem:** Multi-step operations (create/delete relationship) may fail mid-way.

**Solution:** State tracking in MongoDB

- **RelationshipCopyRecord:** Tracks `syncToETMPStatus`, `syncToESStatus`
- **DeleteRecord:** Tracks deletion progress

**Recovery Logic:**

```scala
def createRelationship(arn: Arn, enrolmentKey: EnrolmentKey): Future[Unit] = {
  relationshipCopyRecordRepository.findBy(arn, enrolmentKey).flatMap {
    case Some(record) =>
      // Resume from failure
      if (record.syncToETMPStatus != Success) {
        // Retry ETMP
      }
      if (record.syncToESStatus != Success) {
        // Retry ES
      }
    case None =>
      // Create new record and proceed
      relationshipCopyRecordRepository.create(arn, enrolmentKey).flatMap { _ =>
        // Execute both operations
      }
  }
}
```

Ensures eventual consistency even if process crashes mid-operation.

---

## 10. Audit & Observability

### 10.1 Audit Events

All significant operations generate audit events:

| Event | Trigger |
|-------|---------|
| `AgentClientInvitationSubmitted` | Invitation created |
| `AgentAuthorisationAcceptedByClient` | Client accepted invitation |
| `AgentClientInvitationRejected` | Client rejected invitation |
| `AgentClientInvitationCancelled` | Agent cancelled invitation |
| `AgentClientRelationshipEnded` | Relationship deauthorized |
| `AgentClientRelationshipCreated` | Relationship successfully created |
| `AgentClientRelationshipCreationFailed` | Relationship creation failed |

### 10.2 Logging

- Request/response logging for all API calls
- Service integration logs (ETMP, ES calls)
- MongoDB operation logs
- Error stack traces

### 10.3 Metrics

- Invitation creation rate
- Acceptance/rejection rates
- Relationship creation success/failure rates
- API endpoint latencies
- MongoDB operation times

---

## 11. Frontend Journey Patterns

### 11.1 Agent Create Invitation Journey

**URL Pattern:** `/authorisation-request/*`

**Pages:**

1. `/client-type` - Select personal/business/trust
2. `/select-service` - Choose tax service
3. `/refine-service` - Main vs Supporting (ITSA only)
4. `/client-identifier` - Enter client ID
5. `/client-fact` - Enter known fact
6. `/confirm-client` - Verify client details
7. `/agent-role` - Select role (ITSA only)
8. `/already-manage` - Handle existing relationship
9. `/confirm` - Check your answers
10. `/processing-your-request` - Async call to backend
11. `/confirmation` - Success + invitation link

**Session State:** Stored in Play session (encrypted cookie)

### 11.2 Client Response Journey

**URL Pattern:** `/appoint-someone-to-deal-with-HMRC-for-you/:uid/:name/:service`

**Pages:**

1. Landing page - Display invitation details
2. `/consent-information` - Explain agent access
3. `/confirm-consent` - Accept or decline choice
4. `/confirm-decline` - Confirm rejection (if declining)
5. `/check-answer` - Review (if accepting)
6. `/processing-your-request` - Async backend call
7. `/confirmation` - Success message
8. `/exit-journey/:exitType` - Error exits

**Authentication:** Client must authenticate via Government Gateway

### 11.3 Track Requests Dashboard

**URL:** `/manage-authorisation-requests`

**Features:**

- Paginated table (default 20 per page)
- Status filter (Pending, Accepted, Rejected, Cancelled, Expired)
- Client name search (fuzzy)
- Actions per invitation:
  - **Resend:** Generate new link (for expired invitations)
  - **Cancel:** Cancel pending invitation
  - **Deauth:** Remove accepted relationship
  - **Restart:** Create new invitation (for rejected/expired)

**Backend Calls:**

- GET `/agent/:arn/authorisation-requests` (pagination + filters)
- PUT `/agent/cancel-invitation/:id` (cancel action)
- POST `/agent/:arn/remove-authorisation` (deauth action)

---

## 12. Fast-Track Invitation

### 12.1 Purpose

External systems (e.g., Agent Services Account) can create invitations by POSTing complete data, bypassing the UI journey.

### 12.2 Endpoint

```
POST /agents/fast-track
Content-Type: application/x-www-form-urlencoded

clientType=personal
&service=HMRC-MTD-IT
&clientIdentifierType=ni
&clientIdentifier=AB123456C
&knownFact=AA1+1AA
```

**Returns:** Redirect URL to invitation confirmation page

### 12.3 Use Case

- Agent Services Account dashboard "Request authorisation" button
- Pre-populated forms from external systems
- Bulk invitation creation tools

---

## 13. Testing Strategy

### 13.1 Unit Tests

- Service classes
- Model transformations
- Validation logic
- Encryption/decryption

### 13.2 Integration Tests

**Backend:**

- `AuthorisationAcceptGenericBehaviours` - Common acceptance scenarios
- `AuthorisationAcceptItsaBehaviours` - ITSA-specific flows
- `AuthorisationAcceptAltItsaBehaviours` - Alt ITSA flows
- `InvitationControllerISpec` - Full CRUD operations
- `ClientTaxAgentsDataControllerISpec` - Dashboard data retrieval

**Frontend:**

- Controller specs with stubbed backend
- Journey tests with WireMock
- Form validation tests

### 13.3 Automated E2E Tests

- **agent-services-account-ui-tests:** Full agent journeys
- **agent-gran-perms-acceptance-tests:** Access groups integration
- **agent-helpdesk-ui-tests:** Stride user journeys
- **agent-authorisation-api-acceptance-tests:** API endpoint validation

---

## 14. Deployment & Configuration

### 14.1 Configuration

**Backend:** `application.conf`

- MongoDB connection
- External service URLs (ETMP, ES, Agent Assurance, etc.)
- Invitation expiry duration (default: 21 days)
- Encryption keys
- Feature toggles (e.g., alt-itsa enabled)

**Frontend:** `application.conf`

- Backend URL
- Auth settings
- Session timeout
- Feature flags

### 14.2 Environment Variables

- `MONGODB_URI`: MongoDB connection string
- `AUTH_URL`: Auth service endpoint
- `AGENT_CLIENT_RELATIONSHIPS_URL`: Backend URL (from frontend)

### 14.3 Service Manager Profile

**Profile:** `AGENT_AUTHORISATION`

**Services Started:**

- agent-client-relationships
- agent-client-relationships-frontend
- agent-assurance
- agent-mapping
- auth
- citizen-details
- enrolment-store-proxy
- tax-enrolments
- And dependencies...

---

## 15. Key Insights from Test Analysis

### 15.1 ITSA Complexity

ITSA (Making Tax Digital - Income Tax) has the most complex flows:

- Main vs Supporting agent roles
- NINO → MTDITID resolution
- Alt ITSA (partial auth)
- IRV (Identity Risk Verification) checks
- Agent replacement logic (main can't have supporting, vice versa)

Test coverage is extensive for ITSA edge cases.

### 15.2 Agent Replacement

When client accepts new invitation, system automatically:

1. Finds all other Accepted invitations (same client, same service)
2. Marks them as DeAuthorised
3. Sets `relationshipEndedBy = Client`
4. Does NOT delete relationships (audit trail preserved)

This ensures only one active agent per service per client.

### 15.3 Concurrent Safety

Heavy use of MongoDB locks prevents race conditions:

- Creating relationship while another creates
- Deleting relationship while another deletes
- Concurrent accepts of same invitation

Lock granularity: `(arn, enrolmentKey)`

Returns 423 Locked if contention detected; frontend retries.

### 15.4 Email Notifications

Multiple email types:

- **Acceptance:** Agent notified when client accepts
- **Rejection:** Agent notified when client rejects
- **Warning:** Both notified before expiry (configurable days before)
- **Expired:** Both notified after expiry

Email sending is asynchronous (doesn't block API response).

### 15.5 Data Retention

- Old invitations auto-deleted via MongoDB TTL index
- Accepted/DeAuthorised invitations retained for audit
- Soft delete pattern (status change, not physical deletion)

---

## 16. Limitations & Known Issues

### 16.1 No Invitation History

Client cannot view past invitations they've rejected/accepted (only agents can track).

**Workaround:** Client can view current active relationships via "Manage Your Tax Agents" page.

### 16.2 Email Delivery

Email notifications are "fire and forget" - no retry on failure.

**Impact:** Agent/client may not receive notification if email service down.

### 16.3 Expiry Handling

Expired invitations cannot be directly converted to new invitations - agent must create new invitation with new ID.

**Workaround:** Frontend provides "Restart" action on track requests page.

### 16.4 Concurrent Relationship Creation

If two clients try to accept invitations for same agent simultaneously, one will get 423 Locked.

**Impact:** Client sees error page and must retry.

### 16.5 Partial Failure Recovery

While system tracks state for recovery, there's no automatic retry mechanism - requires manual retry by user.

**Impact:** If relationship creation fails mid-way, user must re-accept invitation (system resumes from failure point).

---

## 17. Security Considerations

### 17.1 Data Protection

- **Encryption at Rest:** Sensitive fields encrypted in MongoDB
- **Encryption in Transit:** HTTPS for all API calls
- **PII Handling:** Client names, agency names encrypted

### 17.2 Authentication

- **Multi-Factor Auth:** Required for client Government Gateway login
- **Agent Credentials:** Separate agent Government Gateway account
- **Stride:** Internal HMRC users via Stride SSO

### 17.3 Authorization

- **Principle of Least Privilege:** Users only see their own data
- **ARN Validation:** Every agent action validates ARN from auth enrolment
- **Client Validation:** Every client action validates client ID from auth enrolment
- **Invitation UID:** Not guessable (Base64 encoded invitationId)

### 17.4 Audit Trail

- All operations logged
- Who, what, when tracked
- Immutable audit records

### 17.5 Rate Limiting

Not explicitly implemented in codebase - relies on platform rate limiting (if any).

**Consideration:** Agents creating bulk invitations could overwhelm system.

---

## 18. Performance Characteristics

### 18.1 MongoDB Queries

- Most operations: O(1) lookup by invitationId (indexed)
- Track requests: O(log n) with pagination
- No full collection scans

### 18.2 External Service Calls

- ETMP: Synchronous, ~500ms avg
- ES: Synchronous, ~200ms avg
- Agent Assurance: Synchronous, ~300ms avg

Total invitation creation: ~1-2 seconds  
Total relationship creation: ~3-5 seconds (dual-track ETMP + ES)

### 18.3 Caching

- Agent records: Cached in agent-assurance (not in ACR)
- Client details: Cached in citizen-details (not in ACR)
- No application-level caching in ACR itself

### 18.4 Scalability

- Stateless services (horizontally scalable)
- MongoDB supports replica sets
- No in-memory state (Play session in encrypted cookie)

**Bottleneck:** External service call latency (ETMP, ES)

---

## 19. Comparison with Other Services

### 19.1 vs. Agent Invitations Frontend (deprecated)

**agent-client-relationships-frontend** replaces the older **agent-invitations-frontend**:

- Broader service support (PIR, Trusts, CGT, PPT, CBC, Pillar 2)
- Improved UX (step-by-step journey)
- Better error handling
- Access groups integration

### 19.2 vs. Agent Authorisation API

**agent-authorisation-api** is the external API wrapper:

- Provides simplified REST API for external systems
- **agent-client-relationships** is the internal service with full business logic
- API routes through to ACR backend

### 19.3 vs. Agent Permissions

**agent-permissions** manages access control within agencies (access groups, team members).

**agent-client-relationships** manages agent-client relationships (invitations, authorizations).

Separate concerns but integrated: ACR checks access groups when validating relationships.

---

## 20. Future Enhancements (Observed from Code)

### 20.1 CBC & Pillar 2 Support

Code shows partial support for:

- **CBC (Country-by-Country):** Reporting for multinational enterprises
- **Pillar 2:** Global minimum tax

Likely pending full rollout.

### 20.2 Enhanced Fast-Track

Fast-track endpoint supports all parameters but frontend only exposes limited fields.

Potential for richer pre-population in future.

### 20.3 Bulk Operations

No bulk invitation creation or bulk deauthorization APIs.

Could be valuable for agencies managing many clients.

### 20.4 Client Invitation History

Clients cannot currently view past invitations they've acted on.

Adding this would improve transparency.

---

## 21. Operational Runbook

### 21.1 Common Issues

**Problem:** Client cannot accept invitation (403 error)

**Causes:**

- Invitation expired
- Client ID mismatch (wrong Government Gateway account)
- Agent suspended

**Resolution:**

1. Check invitation status in MongoDB
2. Verify agent not suspended (agent-assurance)
3. Agent creates new invitation if needed

**Problem:** Relationship creation stuck (423 Locked)

**Causes:**

- Concurrent relationship operation
- Previous operation crashed mid-way (lock not released)

**Resolution:**

1. Check for `RelationshipCopyRecord` or `DeleteRecord` in MongoDB
2. If stale (>30 mins), manually delete lock record
3. Client retries accept

**Problem:** Relationship in ETMP but not ES (or vice versa)

**Causes:**

- Partial failure during relationship creation
- One service down during operation

**Resolution:**

1. Check `RelationshipCopyRecord` for sync status
2. Manually invoke sync operation (or trigger retry)
3. Update sync status flags

### 21.2 Monitoring

**Alerts:**

- Relationship creation failure rate >5%
- MongoDB connection failures
- ETMP/ES service unavailability
- Email service unavailability (warning only)

**Dashboards:**

- Invitation creation rate (per hour)
- Acceptance rate (% of pending invitations accepted)
- Rejection rate
- Relationship creation latency (p50, p95, p99)

### 21.3 Data Cleanup

**Automated:**

- Expired invitations: Deleted via TTL index (configured retention period)
- Stale lock records: Deleted via TTL index (30 minutes)

**Manual:**

- Orphaned relationships (in ETMP but not ES): Requires manual reconciliation script
- Test data in production: Use DELETE endpoints with Stride auth

---

## 22. Conclusion

The Agent Client Relationships system is a mature, production-grade solution for managing agent-client authorizations in the UK tax system. Key strengths include:

✅ **Robust Error Handling:** Recovery from partial failures, MongoDB locking for concurrency  
✅ **Comprehensive Audit:** Full traceability of all operations  
✅ **Dual-Track Consistency:** Maintains sync between ETMP and Enrolment Store  
✅ **Security:** Encryption at rest, strong authentication, authorization checks  
✅ **Complex Business Logic:** Handles ITSA, Alt ITSA, agent replacement, access groups  
✅ **Extensive Test Coverage:** Unit, integration, and E2E tests  

Areas for improvement:
⚠️ Email reliability (no retry mechanism)  
⚠️ Client invitation history (not visible to clients)  
⚠️ Bulk operations (not supported)  
⚠️ Rate limiting (relies on platform)

The tight coupling between frontend and backend is intentional and appropriate - the backend is domain-specific to this workflow and not a general-purpose API.

---

**Document Version:** 1.0  
**Last Updated:** 14 January 2026  
**Author:** System Analysis from Code & Tests  
**Related Services:** agent-assurance, agent-mapping, agent-user-client-details, enrolment-store-proxy, tax-enrolments
