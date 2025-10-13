# agent-client-relationships

## AgentDetailsController

---

## GET /agent/:arn/details

**Description:** Retrieves public details about an agent, such as their name and address.

### Sequence of Interactions

1. **API Call:** `GET /agent-assurance/agent-details/:arn` to `agent-assurance`

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant agent-assurance

    Upstream->>+agent-client-relationships: GET /agent/:arn/details
    agent-client-relationships->>+agent-assurance: GET /agent-assurance/agent-details/:arn
    agent-assurance-->>-agent-client-relationships: Response
    agent-client-relationships-->>-Upstream: Final Response
```
