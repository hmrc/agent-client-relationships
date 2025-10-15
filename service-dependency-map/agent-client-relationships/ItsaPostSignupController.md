# agent-client-relationships

## ItsaPostSignupController

---

## POST /itsa-post-signup/create-relationship/:nino

**Description:** Creates an ITSA relationship for a client after they have signed up, by either converting a partial-auth relationship or copying a legacy SA relationship.

### Sequence of Interactions

1. **API Call:** `GET /registration/individual/nino/:nino` to `if-or-hip`
2. **Database:** Read/Update: Check for a partial-auth record and attempt to create a full relationship from it in `agent-client-relationships-db (partial-auth)`.
3. **API Call:** `GET /registration/relationship/nino/:nino` to `des` (If no partial-auth)
4. **API Call:** `POST /enrolment-store-proxy/enrolment-store/groups/:groupId/enrolments/:enrolmentKey` to `enrolment-store-proxy` (If legacy relationship found)

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant if-or-hip
    participant Database
    participant des
    participant enrolment-store-proxy

    Upstream->>+agent-client-relationships: POST /itsa-post-signup/create-relationship/:nino
    agent-client-relationships->>+if-or-hip: GET /registration/individual/nino/:nino
    if-or-hip-->>-agent-client-relationships: Response (MTDITID)
    
    alt Partial Auth Exists
        agent-client-relationships->>Database: Read/Update: Check for and use partial-auth record.
        Database-->>agent-client-relationships: Response
    else Legacy SA Relationship Exists
        agent-client-relationships->>+des: GET /registration/relationship/nino/:nino
        des-->>-agent-client-relationships: Response (Legacy SA Agents)
        agent-client-relationships->>+enrolment-store-proxy: POST /enrolment-store-proxy/enrolment-store/groups/:groupId/enrolments/:enrolmentKey
        enrolment-store-proxy-->>-agent-client-relationships: Response
    end

    agent-client-relationships-->>-Upstream: Final Response
```
