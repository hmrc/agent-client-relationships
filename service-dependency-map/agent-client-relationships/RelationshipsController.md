# agent-client-relationships

## RelationshipsController

---

## GET /agent/:arn/service/:service/client/:clientIdType/:clientId

**Description:** Checks if a relationship exists between an agent and a client for a specific service.

### Sequence of Interactions

1. **API Call:** `GET /registration/relationship/arn/:arn/service/:service/id/:id` to `des`
2. **API Call:** `GET /if/registration/relationship/arn/:arn/service/:service/id/:id` to `if-or-hip`
3. **API Call:** `GET /enrolment-store-proxy/enrolment-store/enrolments/:enrolmentKey/groups` to `enrolment-store-proxy`

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant des
    participant if-or-hip
    participant enrolment-store-proxy

    Upstream->>+agent-client-relationships: GET /agent/:arn/service/:service/client/:clientIdType/:clientId
    agent-client-relationships->>+des: GET /registration/relationship/arn/:arn/service/:service/id/:id
    des-->>-agent-client-relationships: Response
    agent-client-relationships->>+if-or-hip: GET /if/registration/relationship/arn/:arn/service/:service/id/:id
    if-or-hip-->>-agent-client-relationships: Response
    agent-client-relationships->>+enrolment-store-proxy: GET /enrolment-store-proxy/enrolment-store/enrolments/:enrolmentKey/groups
    enrolment-store-proxy-->>-agent-client-relationships: Response
    agent-client-relationships-->>-Upstream: Final Response
```

---

## GET /agent/relationships/inactive

**Description:** Retrieves a list of all inactive (terminated) relationships for the authenticated agent.

### Sequence of Interactions

1. **Database:** Read: Find all invitations for the agent's ARN that have a status of 'Accepted' and have an 'ended' date in `agent-client-relationships-db (invitations)`.

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant Database

    Upstream->>+agent-client-relationships: GET /agent/relationships/inactive
    agent-client-relationships->>Database: Read: Find all invitations for the agent's ARN that have a status of 'Accepted' and have an 'ended' date.
    agent-client-relationships-->>-Upstream: Final Response
```

---

## DELETE /agent/:arn/terminate

**Description:** Terminates all of an agent's client relationships. This is a destructive, agent-initiated action.

### Sequence of Interactions

1. **Database:** Read: Find all active relationships for the given ARN in `agent-client-relationships-db (invitations)`.
2. **API Call:** `DELETE /enrolment-store-proxy/enrolment-store/groups/:groupId/enrolments/:enrolmentKey` to `enrolment-store-proxy`
3. **Database:** Update: For each active relationship, mark the invitation as 'Accepted' and set relationship ended in `agent-client-relationships-db (invitations)`.

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant enrolment-store-proxy
    participant Database

    Upstream->>+agent-client-relationships: DELETE /agent/:arn/terminate
    agent-client-relationships->>Database: Read: Find all active relationships for the given ARN.
    agent-client-relationships->>+enrolment-store-proxy: DELETE /enrolment-store-proxy/enrolment-store/groups/:groupId/enrolments/:enrolmentKey
    enrolment-store-proxy-->>-agent-client-relationships: Response
    agent-client-relationships->>Database: Update: For each active relationship, mark the invitation as 'Accepted' and set relationship ended.
    agent-client-relationships-->>-Upstream: Final Response
```

---

## GET /agent/:arn/client/:nino/legacy-mapped-relationship

**Description:** Checks if a client has a legacy SA relationship in CESA and if that relationship has been mapped to the agent's ARN.

### Sequence of Interactions

1. **API Call:** `GET /registration/individual/nino/:nino` to `des`
2. **API Call:** `GET /agent-mapping/mappings/sa/:arn` to `agent-mapping`

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant des
    participant agent-mapping

    Upstream->>+agent-client-relationships: GET /agent/:arn/client/:nino/legacy-mapped-relationship
    agent-client-relationships->>+des: GET /registration/individual/nino/:nino
    des-->>-agent-client-relationships: Response
    agent-client-relationships->>+agent-mapping: GET /agent-mapping/mappings/sa/:arn
    agent-mapping-->>-agent-client-relationships: Response
    agent-client-relationships-->>-Upstream: Final Response
```

---

## GET /client/relationships/active

**Description:** Retrieves all active relationships for the authenticated client across all their enrolled services.

### Sequence of Interactions

1. **API Call:** `GET /hip/relationships/agent/:arn/service/:service/client/:clientId` to `hip`

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant hip

    Upstream->>+agent-client-relationships: GET /client/relationships/active
    agent-client-relationships->>+hip: GET /hip/relationships/agent/:arn/service/:service/client/:clientId
    hip-->>-agent-client-relationships: Response
    agent-client-relationships-->>-Upstream: Final Response
```

---

## GET /client/relationships/inactive

**Description:** Retrieves all inactive (terminated) relationships for the authenticated client.

### Sequence of Interactions

1. **Database:** Read: Find all invitations for the client's identifiers that have a status of 'Accepted' and have an 'ended' date in `agent-client-relationships-db (invitations)`.

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant Database

    Upstream->>+agent-client-relationships: GET /client/relationships/inactive
    agent-client-relationships->>Database: Read: Find all invitations for the client's identifiers that have a status of 'Accepted' and have an 'ended' date.
    agent-client-relationships-->>-Upstream: Final Response
```

---

## GET /client/relationships/service/:service

**Description:** Retrieves the active agent relationship for a specific service for the authenticated client.

### Sequence of Interactions

1. **API Call:** `GET /hip/relationships/agent/:arn/service/:service/client/:clientId` to `hip`

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant hip

    Upstream->>+agent-client-relationships: GET /client/relationships/service/:service
    agent-client-relationships->>+hip: GET /hip/relationships/agent/:arn/service/:service/client/:clientId
    hip-->>-agent-client-relationships: Response
    agent-client-relationships-->>-Upstream: Final Response
```

---

## GET /relationships/service/:service/client/:clientIdType/:clientId

**Description:** Allows a Stride user to retrieve active relationships for a specific client.

### Sequence of Interactions

1. **Database:** Read: Find active relationships for the given client identifier and service in `agent-client-relationships-db (relationships)`.

### Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    participant Upstream
    participant agent-client-relationships
    participant Database

    Upstream->>+agent-client-relationships: GET /relationships/service/:service/client/:clientIdType/:clientId
    agent-client-relationships->>Database: Read: Find active relationships for the given client identifier and service.
    agent-client-relationships-->>-Upstream: Final Response
```
