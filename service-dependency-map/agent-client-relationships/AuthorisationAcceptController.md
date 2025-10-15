# agent-client-relationships

## AuthorisationAcceptController

---

## PUT /authorisation-response/accept/:invitationId

**Description:** Allows a client to accept a pending authorisation request (invitation), which creates an active relationship.

### Sequence of Interactions

1. **Database:** Read: Find the invitation by its ID in `agent-client-relationships-db (invitations)`.
2. **API Call:** `POST /enrolment-store-proxy/enrolment-store/groups/:groupId/enrolments/:enrolmentKey` to `enrolment-store-proxy`
3. **Database:** Update: Update the invitation status to 'Accepted' in `agent-client-relationships-db (invitations)`.

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant Database
    participant enrolment-store-proxy

    Upstream->>+agent-client-relationships: PUT /authorisation-response/accept/:invitationId
    agent-client-relationships->>Database: Read: Find the invitation by its ID.
    agent-client-relationships->>+enrolment-store-proxy: POST /enrolment-store-proxy/enrolment-store/groups/:groupId/enrolments/:enrolmentKey
    enrolment-store-proxy-->>-agent-client-relationships: Response
    agent-client-relationships->>Database: Update: Update the invitation status to 'Accepted'.
    agent-client-relationships-->>-Upstream: Final Response
```
