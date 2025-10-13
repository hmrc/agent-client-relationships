# agent-client-relationships

## StrideGetPartialAuthsController

---

## GET /stride/partial-auths/nino/:nino

**Description:** Allows a Stride user to retrieve partial-authorisation records for a client's NINO.

### Sequence of Interactions

1. **Database:** Read: Get all partial-auth records for the given NINO in `agent-client-relationships-db (partial-auth)`.
2. **API Call:** `GET /citizen-details/nino/:nino` to `citizen-details`
3. **API Call:** `GET /agent-assurance/agent-details/:arn` to `agent-assurance`

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant Database
    participant citizen-details
    participant agent-assurance

    Upstream->>+agent-client-relationships: GET /stride/partial-auths/nino/:nino
    agent-client-relationships->>Database: Read: Get all partial-auth records for the given NINO.
    agent-client-relationships->>+citizen-details: GET /citizen-details/nino/:nino
    citizen-details-->>-agent-client-relationships: Response
    agent-client-relationships->>+agent-assurance: GET /agent-assurance/agent-details/:arn
    agent-assurance-->>-agent-client-relationships: Response
    agent-client-relationships-->>-Upstream: Final Response
```
