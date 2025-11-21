# agent-client-relationships

## ApiCheckRelationshipController

---

## POST /api/:arn/relationship

**Description:** Checks if a relationship exists between an agent and a client, performing validation checks.

### Sequence of Interactions

1. **API Call:** `GET /agent-assurance/agent-details/:arn` to `agent-assurance`
2. **API Call:** `GET /registration/business-details/... or /vat/customer/vrn/...` to `des`
3. **Database:** Read: Check for an existing active relationship in the relationships repository in `agent-client-relationships-db (relationships)`.
4. **API Call:** `GET /enrolment-store-proxy/enrolment-store/enrolments/:enrolmentKey/groups` to `enrolment-store-proxy` (If not in relationships repo)

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant agent-assurance
    participant des
    participant Database
    participant enrolment-store-proxy

    Upstream->>+agent-client-relationships: POST /api/:arn/relationship
    agent-client-relationships->>+agent-assurance: GET /agent-assurance/agent-details/:arn
    agent-assurance-->>-agent-client-relationships: Response
    agent-client-relationships->>+des: GET /registration/business-details/... or /vat/customer/vrn/...
    des-->>-agent-client-relationships: Response
    agent-client-relationships->>Database: Read: Check for active relationship.
    alt Relationship not in local DB
        agent-client-relationships->>+enrolment-store-proxy: GET /enrolment-store-proxy/enrolment-store/enrolments/:enrolmentKey/groups
        enrolment-store-proxy-->>-agent-client-relationships: Response
    end
    agent-client-relationships-->>-Upstream: Final Response
```
