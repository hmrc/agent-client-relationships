# ACR29: Stride Get IRV Relationships

## Overview

Allows a Stride user (HMRC internal staff) to retrieve active IRV (Income Record Viewer / Personal Income Record) relationships for a client identified by NINO. Validates NINO format, queries agent-fi-relationship service for active IRV relationships, enriches each relationship with agent name from Agent Maintainer, and retrieves client name from DES Citizen Details. IRV is a specific service allowing agents to view client income records. Returns client name, NINO, and list of agents with IRV access. Used by HMRC staff for customer support to view IRV authorization status.

## API Details

- **API ID**: ACR29
- **Method**: GET
- **Path**: `/stride/irv-relationships/{nino}`
- **Authentication**: Stride authentication with specific roles (maintain_agent_relationships or maintain_agent_manually_assure)
- **Audience**: Internal (HMRC staff only)
- **Controller**: StrideClientDetailsController
- **Controller Method**: `getIrvRelationships`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| nino | String | Yes | Client National Insurance Number (must be valid NINO format, e.g., AB123456C) |

## Query Parameters

None

## Request Body

None

## Response

### Success Response (200 OK)

```json
{
  "clientName": "Matthew Kovacic",
  "nino": "AB123456C",
  "agents": [
    {
      "name": "ABC Ltd",
      "arn": "AARN0000002"
    },
    {
      "name": "XYZ Tax Services",
      "arn": "AARN1234567"
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| clientName | String | Client name from DES Citizen Details |
| nino | String | Client NINO (from request) |
| agents | Array | Array of IrvAgent objects (can be empty if no relationships) |

### IrvAgent

| Field | Type | Description |
|-------|------|-------------|
| name | String | Agent name from Agent Maintainer |
| arn | String | Agent Reference Number |

### Error Responses

| Status Code | Description | Example Body |
|-------------|-------------|--------------|
| 400 | Bad Request - Invalid NINO format | N/A |
| 401 | Unauthorized - Stride authentication failed | N/A |
| 403 | Forbidden - Stride user does not have required role | N/A |
| 404 | Not Found - Client details not found in DES | N/A |
| 500 | Internal Server Error - Error retrieving client details, agent details, or relationships | Error message |

## Service Architecture

See [ACR29.mmd](ACR29.mmd) for complete sequence diagram.

### Flow Summary

1. **Stride Authorization**: Check Stride authentication and roles
2. **NINO Validation**: Validate NINO format
3. **IRV Relationship Retrieval**: Query agent-fi-relationship for active IRV relationships
4. **Error Recovery**: RelationshipNotFound and RelationshipSuspended recovered as empty agents list
5. **Agent Name Enrichment**: Retrieve agent name from Agent Maintainer for each ARN
6. **Client Name Retrieval**: Retrieve client name from DES Citizen Details
7. **Response**: 200 with IrvRelationships (client name, NINO, and agents list)

## Business Logic

### NINO Validation

Uses ValidationService.validateForTaxIdentifier with NinoType.id:
- Validates NINO format (e.g., AB123456C)
- Returns 400 Bad Request if invalid
- Validation occurs before Stride authorization (efficient rejection)

### IRV Relationship Retrieval

Queries agent-fi-relationship service with service=PERSONAL-INCOME-RECORD:
- **IRV relationships are NOT stored in HIP/ETMP**
- They're managed separately in agent-fi-relationship service
- Returns list of ClientRelationship objects

### Error Recovery

Special handling for relationship errors:
- **RelationshipNotFound**: Recovered as empty sequence (agents = [])
- **RelationshipSuspended**: Recovered as empty sequence (agents = [])
- Allows request to succeed with client details but no agents
- Other errors propagate and result in 500

### Agent Name Enrichment

For each relationship returned:
1. Extract ARN from relationship
2. Call Agent Maintainer to retrieve agent name
3. Build IrvAgent object with name and ARN
4. Failure to retrieve agent name results in 500 error

### Client Details Retrieval

Uses ClientDetailsService.findClientDetails:
- Service: "PERSONAL-INCOME-RECORD"
- Queries DES getCitizenDetails endpoint
- Returns client name
- **404 if client not found** in DES
- **500 if DES error**

### Stride Authorization

Requires Stride authentication with one of these roles:
- `maintain_agent_relationships`
- `maintain_agent_manually_assure`

## Dependencies

### External Services

| Service | Method | Purpose | Note |
|---------|--------|---------|------|
| Agent-Fi-Relationship | GET /relationships/active?service=PERSONAL-INCOME-RECORD | Retrieve active IRV relationships | IRV relationships managed separately from HIP |
| DES (Citizen Details) | getCitizenDetails | Retrieve client name for NINO | Returns individual's name |
| Agent Maintainer | GET /agent-maintainer/agent/{arn} | Retrieve agent name for each ARN | Called once per unique ARN |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| StrideClientDetailsService | findActiveIrvRelationships | Orchestrate entire flow |
| ValidationService | validateForTaxIdentifier | Validate NINO format |
| AgentFiRelationshipConnector | findIrvActiveRelationshipForClient | Query agent-fi-relationship |
| ClientDetailsService | findClientDetails | Query DES for client name |
| AgentMaintainerConnector | getAgentRecord | Retrieve agent name |

### Database Collections

None - All data from external services

## Use Cases

### 1. Client with Active IRV Relationships

**Scenario**: Client has 2 agents with IRV access

**Flow**:
1. Stride user calls endpoint with valid NINO
2. NINO validation passes
3. Stride authorization succeeds
4. agent-fi-relationship returns 2 active IRV relationships
5. Agent names retrieved for both ARNs from Agent Maintainer
6. Client name retrieved from DES Citizen Details
7. 200 OK with client details and 2 agents

**Response**:
```json
{
  "clientName": "Matthew Kovacic",
  "nino": "AB123456C",
  "agents": [
    {
      "name": "ABC Ltd",
      "arn": "AARN0000002"
    },
    {
      "name": "XYZ Tax Services",
      "arn": "AARN1234567"
    }
  ]
}
```

**Frontend Action**: Display client name with list of agents having IRV access

### 2. Client with No IRV Relationships

**Scenario**: Client has no IRV relationships

**Flow**:
1. Stride user calls endpoint with valid NINO
2. NINO validation passes
3. Stride authorization succeeds
4. agent-fi-relationship returns RelationshipNotFound
5. Error recovered as empty agents list
6. Client name retrieved from DES
7. 200 OK with client details but empty agents array

**Response**:
```json
{
  "clientName": "John Doe",
  "nino": "CD987654A",
  "agents": []
}
```

**Frontend Action**: Display "No IRV relationships found for this client"

### 3. Client with Suspended IRV Relationships

**Scenario**: Client's IRV relationships are suspended

**Flow**:
1. Stride user calls endpoint
2. Validation and authorization succeed
3. agent-fi-relationship returns RelationshipSuspended
4. Error recovered as empty agents list
5. Client name retrieved from DES
6. 200 OK with empty agents array

**Response**:
```json
{
  "clientName": "Jane Smith",
  "nino": "EF123456B",
  "agents": []
}
```

**Frontend Action**: Display "No active IRV relationships (may be suspended)"

### 4. Invalid NINO Format

**Scenario**: Stride user provides invalid NINO

**Flow**:
1. Stride user provides invalid NINO (e.g., "INVALID_NINO")
2. NINO validation fails
3. 400 Bad Request returned

**Response**: 400 Bad Request - TaxIdentifierError

**Frontend Action**: Show "Invalid NINO format" error

### 5. Client Not Found in DES

**Scenario**: NINO not found in Citizen Details

**Flow**:
1. Stride user calls with valid NINO format
2. Validation and authorization succeed
3. IRV relationships retrieved (or recovered as empty)
4. DES Citizen Details returns 404
5. 404 Not Found returned

**Response**: 404 Not Found - ClientDetailsNotFound

**Frontend Action**: Show "Client not found" error

### 6. Agent Maintainer Unavailable

**Scenario**: Agent Maintainer service unavailable

**Flow**:
1. Stride user calls endpoint
2. Validation, authorization, and IRV retrieval succeed
3. Agent Maintainer returns 500 when retrieving agent name
4. 500 Internal Server Error returned

**Response**: 500 Internal Server Error - ErrorRetrievingAgentDetails

**Frontend Action**: Show error message, suggest retry

### 7. DES Citizen Details Unavailable

**Scenario**: DES service unavailable

**Flow**:
1. Stride user calls endpoint
2. Validation, authorization, and IRV retrieval succeed
3. DES returns 500 error
4. 500 Internal Server Error returned

**Response**: 500 Internal Server Error - ErrorRetrievingClientDetails: "Unexpected error during 'getItsaCitizenDetails'"

**Frontend Action**: Show error message, suggest retry

### 8. agent-fi-relationship Service Unavailable

**Scenario**: agent-fi-relationship service returns error

**Flow**:
1. Stride user calls endpoint
2. Validation and authorization succeed
3. agent-fi-relationship returns 500 error (not NotFound/Suspended)
4. 500 Internal Server Error returned

**Response**: 500 Internal Server Error - ErrorRetrievingRelationship

**Frontend Action**: Show error message, suggest retry

## Error Handling

| Error Scenario | Response | Message | Note |
|----------------|----------|---------|------|
| Invalid NINO format | 400 Bad Request | TaxIdentifierError | NINO must be valid format |
| Stride authentication failure | 401 Unauthorized | Authentication failed | Must be Stride authenticated |
| Insufficient Stride permissions | 403 Forbidden | Forbidden | Must have required Stride role |
| Client not found in DES | 404 Not Found | ClientDetailsNotFound | NINO not in Citizen Details |
| IRV relationships not found | 200 OK with empty agents | N/A | RelationshipNotFound recovered |
| IRV relationships suspended | 200 OK with empty agents | N/A | RelationshipSuspended recovered |
| DES error | 500 Internal Server Error | ErrorRetrievingClientDetails | DES unavailable |
| Agent Maintainer error | 500 Internal Server Error | ErrorRetrievingAgentDetails | Agent Maintainer unavailable |
| agent-fi-relationship error | 500 Internal Server Error | ErrorRetrievingRelationship | Service unavailable |
| Other errors | 500 Internal Server Error | Generic error | Unexpected failures |

## Important Notes

- ✅ Stride-only endpoint - not accessible to agents or clients
- ✅ IRV-specific endpoint - only retrieves PERSONAL-INCOME-RECORD relationships
- ✅ Queries agent-fi-relationship service, NOT HIP/ETMP
- ✅ IRV relationships managed separately from other services
- ✅ Each agent ARN enriched with agent name from Agent Maintainer
- ✅ Client name always retrieved from DES Citizen Details
- ✅ RelationshipNotFound and RelationshipSuspended are gracefully recovered
- ✅ Returns 200 with empty agents array if no IRV relationships found
- ✅ NINO-only endpoint - not for other identifier types
- ⚠️ Returns 404 only if client not found in DES, not if no relationships
- ⚠️ If no IRV relationships, returns 200 with empty agents array (not 404)
- ⚠️ Agent Maintainer failure results in 500, not graceful degradation
- ⚠️ DES failure results in 404 (not found) or 500 (error), not graceful degradation
- ⚠️ agent-fi-relationship errors (except NotFound/Suspended) result in 500
- ⚠️ Each agent ARN requires Agent Maintainer call - could be slow with many agents
- ⚠️ NINO must be valid format - validation occurs before Stride auth
- ⚠️ IRV is separate service from MTD-IT - different data source
- ⚠️ Requires specific Stride roles: maintain_agent_relationships or maintain_agent_manually_assure
- ⚠️ No database queries - all data from external services
- ⚠️ Response can contain multiple agents if client has IRV relationships with multiple agents

## Related Documentation

- **ACR27**: Stride Get Active Relationships (Bulk) - Includes IRV relationships for NINO clients
- **ACR28**: Stride Get Client Details - Includes active main agent (may be IRV for IRV service)
- **AFR03**: Agent-Fi-Relationship API - Source of IRV relationship data

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
