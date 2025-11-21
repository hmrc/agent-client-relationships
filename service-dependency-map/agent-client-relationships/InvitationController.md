# agent-client-relationships

## InvitationController

---

## PUT /client/authorisation-response/reject/:invitationId

**Description:** Allows a client to reject a pending authorisation request (invitation).

### Sequence of Interactions

1. **Database:** Update: Find the invitation by ID and update its status to 'Rejected' in `agent-client-relationships-db (invitations)`.

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant Database

    Upstream->>+agent-client-relationships: PUT /client/authorisation-response/reject/:invitationId
    agent-client-relationships->>Database: Update: Find the invitation by ID and update its status to 'Rejected'.
    agent-client-relationships-->>-Upstream: Final Response
```

---

## POST /agent/:arn/authorisation-request

**Description:** Allows an agent to create a new authorisation request (invitation) for a client.

### Sequence of Interactions

1. **API Call:** `GET /registration/individual/nino/:nino` to `des`
2. **Database:** Create: Create a new invitation record with a 'Pending' status in `agent-client-relationships-db (invitations)`.

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant des
    participant Database

    Upstream->>+agent-client-relationships: POST /agent/:arn/authorisation-request
    agent-client-relationships->>+des: GET /registration/individual/nino/:nino
    des-->>-agent-client-relationships: Response
    agent-client-relationships->>Database: Create: Create a new invitation record with a 'Pending' status.
    agent-client-relationships-->>-Upstream: Final Response
```

---

## PUT /agent/cancel-invitation/:invitationId

**Description:** Allows an agent to cancel a pending authorisation request (invitation) they have previously sent.

### Sequence of Interactions

1. **Database:** Update: Find the invitation by ID and agent's ARN, and update its status to 'Cancelled' in `agent-client-relationships-db (invitations)`.

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant Database

    Upstream->>+agent-client-relationships: PUT /agent/cancel-invitation/:invitationId
    agent-client-relationships->>Database: Update: Find the invitation by ID and agent's ARN, and update its status to 'Cancelled'.
    agent-client-relationships-->>-Upstream: Final Response
```

---

## POST /invitations/trusts-enrolment-orchestrator/:urn/update

**Description:** Updates a trust-related invitation by replacing the temporary URN with the permanent UTR after registration.

### Sequence of Interactions

1. **Database:** Update: Find the invitation by the URN and update it with the new UTR in `agent-client-relationships-db (invitations)`.

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant Database

    Upstream->>+agent-client-relationships: POST /invitations/trusts-enrolment-orchestrator/:urn/update
    agent-client-relationships->>Database: Update: Find the invitation by the URN and update it with the new UTR.
    agent-client-relationships-->>-Upstream: Final Response
```
