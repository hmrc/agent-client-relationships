# ACR27: Stride Get Active Relationships (Bulk)

## Overview

⚠️ **VERY HIGH COMPLEXITY** - Allows a Stride user (HMRC internal staff) to retrieve ALL active agent-client relationships for multiple clients in a single request. Supports all client identifier types (NINO, VRN, UTR, URN, CGT, PPT, CBC, PLR). For each client, retrieves relationships from HIP/IF/Agent-Fi-Relationship, enriches with agent names from Agent Maintainer and client names from various DES services. Special handling for NINO includes ITSA (MTD-IT + MTD-IT-SUPP), IRV (Personal Income Record), and PartialAuth relationships retrieved in parallel. Used by HMRC staff for comprehensive relationship views across multiple clients.

## API Details

- **API ID**: ACR27
- **Method**: POST
- **Path**: `/stride/active-relationships`
- **Authentication**: Stride authentication with specific roles (maintain_agent_relationships or maintain_agent_manually_assure)
- **Audience**: Internal (HMRC staff only)
- **Controller**: StrideClientDetailsController
- **Controller Method**: `getActiveRelationships`

## Path Parameters

None

## Query Parameters

None

## Request Body

```json
{
  "clientRelationshipRequest": [
    {
      "clientIdType": "NINO",
      "clientId": "AB123456C"
    },
    {
      "clientIdType": "VRN",
      "clientId": "123456789"
    },
    {
      "clientIdType": "UTR",
      "clientId": "1234567890"
    }
  ]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| clientRelationshipRequest | Array | Yes | Array of ClientRelationshipRequest objects |

### ClientRelationshipRequest

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| clientIdType | String | Yes | Client identifier type (NINO, VRN, UTR, URN, CGTPDRef, EtmpRegistrationNumber, cbcId, PLRID) |
| clientId | String | Yes | Client identifier value (must be valid format for type) |

## Response

### Success Response (200 OK)

```json
{
  "activeClientRelationships": [
    {
      "clientId": "AB123456C",
      "clientName": "John Doe",
      "arn": "AARN1234567",
      "agentName": "ABC Accountants Ltd",
      "service": "HMRC-MTD-IT"
    },
    {
      "clientId": "AB123456C",
      "clientName": "John Doe",
      "arn": "AARN1234567",
      "agentName": "ABC Accountants Ltd",
      "service": "HMRC-MTD-IT-SUPP"
    },
    {
      "clientId": "AB123456C",
      "clientName": "John Doe",
      "arn": "AARN7654321",
      "agentName": "XYZ Tax Services",
      "service": "PERSONAL-INCOME-RECORD"
    },
    {
      "clientId": "123456789",
      "clientName": "Acme Ltd",
      "arn": "AARN1111111",
      "agentName": "VAT Specialists",
      "service": "HMRC-MTD-VAT"
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| activeClientRelationships | Array | Array of ActiveClientRelationship objects |

### ActiveClientRelationship

| Field | Type | Description |
|-------|------|-------------|
| clientId | String | Client identifier value from request |
| clientName | String | Client name retrieved from DES |
| arn | String | Agent Reference Number |
| agentName | String | Agent name retrieved from Agent Maintainer |
| service | String | Service identifier (e.g., HMRC-MTD-IT, HMRC-MTD-VAT, HMRC-TERS-ORG) |

### Error Responses

| Status Code | Description | Example Body |
|-------------|-------------|--------------|
| 400 | Bad Request - Invalid clientIdType or clientId format | N/A |
| 401 | Unauthorized - Stride authentication failed | N/A |
| 403 | Forbidden - Stride user does not have required role | N/A |
| 404 | Not Found - Client details not found | N/A |
| 500 | Internal Server Error - Error retrieving relationships, agent details, or client details | Error message |

## Service Architecture

See [ACR27.mmd](ACR27.mmd) for complete sequence diagram.

### Flow Summary

1. **Stride Authorization**: Check Stride authentication and roles
2. **Bulk Processing**: Process each client in the request array
3. **For each client**:
   - **Validation**: Validate clientIdType and clientId format
   - **Relationship Retrieval**:
     - **NINO**: Parallel queries to (1) HIP via IF for ITSA, (2) Agent-Fi-Relationship for IRV, (3) MongoDB for PartialAuth
     - **Other identifiers**: Query HIP directly
   - **Enrichment**: Add agent names from Agent Maintainer and client names from DES
4. **Combine**: Flatten all relationships from all clients
5. **Response**: 200 with complete list, or error if any step fails

## Business Logic

### Bulk Processing

The endpoint processes multiple clients in a single request. Each `ClientRelationshipRequest` is processed using EitherT monad transformation:
- All results are sequenced and flattened into a single list
- A failure in any client request causes the entire operation to fail with appropriate error
- No partial success - all or nothing

### NINO Special Handling

NINO clients have **three relationship sources** retrieved in parallel:

1. **ITSA Relationships** (HIP via IF):
   - Convert NINO to MtdItId via IF
   - Query HIP for MTD-IT and MTD-IT-SUPP relationships
   - Returns main agent relationships

2. **IRV Relationships** (Agent-Fi-Relationship):
   - Query agent-fi-relationship service directly
   - Returns PERSONAL-INCOME-RECORD relationships
   - Errors are recovered (doesn't fail request)

3. **PartialAuth Relationships** (MongoDB):
   - Query local partial-auth collection
   - Returns supporting agent relationships for MTD-IT
   - Supporting agents have limited access

All three sources are queried in parallel, then combined into a single list.

### Other Identifier Handling

Non-NINO identifiers (VRN, UTR, URN, CGT, PPT, CBC, PLR) query HIP directly:
- Service type determined by identifier type
- Single query to HIP via `getAllRelationshipsForClient`
- activeOnly=true ensures only active relationships returned

### Relationship Enrichment

Each relationship is enriched with names:

1. **Agent Name**: Retrieved from Agent Maintainer using ARN
   - Separate call per unique ARN
   - Failure causes 500 error

2. **Client Name**: Retrieved from appropriate DES service:
   - **NINO**: Citizen Details + Designatory Details
   - **VRN**: getVrnDisplayDetails
   - **UTR/URN**: getTrustDetails
   - **CGTPDRef**: getCgtSubscriptionDetails
   - **EtmpRegistrationNumber**: getPptSubscriptionDetails
   - **cbcId**: getCbcSubscriptionDetails
   - **PLRID**: getPillar2SubscriptionDetails

Produces `ActiveClientRelationship` objects with full details.

### Active Filtering

Only active relationships are returned:
- `activeOnly=true` passed to all relationship queries
- HIP and IF results further filtered with `.filter(_.isActive)`
- Ensures only truly active relationships (isActive=true) included

### Error Recovery

Some errors are recovered to not break bulk processing:
- **IRV RelationshipNotFound**: Recovered as empty sequence
- **IRV RelationshipSuspended**: Recovered as empty sequence
- Allows NINO processing to continue even if IRV fails
- Other errors propagate and fail the entire request

### Stride Authorization

Requires Stride authentication with one of these roles:
- `maintain_agent_relationships`
- `maintain_agent_manually_assure`

## Dependencies

### External Services

| Service | Method | Purpose | Note |
|---------|--------|---------|------|
| HIP (ETMP) | GET /registration/relationship | Retrieve active relationships for all services except IRV | Called for each non-NINO client, and for ITSA relationships for NINO |
| IF (Integration Framework) | getMtdIdFor | Convert NINO to MtdItId | Only for NINO clients |
| Agent-Fi-Relationship | GET /relationships/active | Retrieve IRV relationships for NINO | Only for NINO. IRV not in HIP |
| Citizen Details (DES) | getCitizenDetails | Retrieve client name for NINO | Individual's name |
| Designatory Details (DES) | getDesignatoryDetails | Additional client details for NINO | Supplementary details |
| DES | Various subscription endpoints | Retrieve client names for VRN, UTR, URN, CGT, PPT, CBC, PLR | Different endpoint per type |
| Agent Maintainer | getAgentRecord | Retrieve agent name for each ARN | Called once per unique ARN |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| StrideClientDetailsService | findAllActiveRelationship | Orchestrate entire flow |
| ValidationService | validateForTaxIdentifier | Validate clientIdType and clientId |
| FindRelationshipsService | getAllActiveItsaRelationshipForClient | ITSA relationships for NINO |
| FindRelationshipsService | getAllRelationshipsForClient | Relationships for non-NINO |
| AgentFiRelationshipConnector | findIrvActiveRelationshipForClient | IRV relationships for NINO |
| PartialAuthRepository | findActiveByNino | PartialAuth relationships from MongoDB |
| ClientDetailsService | findClientDetailsByTaxIdentifier | Route to appropriate DES service |
| AgentMaintainerConnector | getAgentRecord | Retrieve agent name |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| partial-auth | READ | PartialAuth relationships for NINO. Supporting agents with limited MTD-IT access |

## Use Cases

### 1. Bulk Relationship Lookup for Multiple Clients

**Scenario**: Stride user needs to view relationships for 3 clients (NINO, VRN, UTR)

**Flow**:
1. Stride user authenticated with required role
2. Calls endpoint with array of 3 ClientRelationshipRequest objects
3. Each client validated
4. NINO: ITSA, IRV, and PartialAuth relationships retrieved in parallel
5. VRN: HIP queried for VAT relationships
6. UTR: HIP queried for Trust relationships
7. All relationships enriched with agent and client names
8. 200 OK returned with combined list

**Response**:
```json
{
  "activeClientRelationships": [
    {
      "clientId": "AB123456C",
      "clientName": "John Doe",
      "arn": "AARN1234567",
      "agentName": "ABC Accountants",
      "service": "HMRC-MTD-IT"
    },
    {
      "clientId": "AB123456C",
      "clientName": "John Doe",
      "arn": "AARN1234567",
      "agentName": "ABC Accountants",
      "service": "HMRC-MTD-IT-SUPP"
    },
    {
      "clientId": "123456789",
      "clientName": "Acme Ltd",
      "arn": "AARN7654321",
      "agentName": "VAT Specialists",
      "service": "HMRC-MTD-VAT"
    },
    {
      "clientId": "1234567890",
      "clientName": "Smith Family Trust",
      "arn": "AARN9999999",
      "agentName": "Trust Advisors Ltd",
      "service": "HMRC-TERS-ORG"
    }
  ]
}
```

**Frontend Action**: Display comprehensive table of all relationships across all clients

### 2. NINO Client with Multiple Relationship Types

**Scenario**: Client has main agent, supporting agent, and IRV agent

**Flow**:
1. Stride user requests relationships for one NINO
2. Validation passes
3. Parallel retrieval:
   - ITSA from HIP via IF → MTD-IT main agent + MTD-IT-SUPP
   - IRV from Agent-Fi-Relationship → PERSONAL-INCOME-RECORD
   - PartialAuth from MongoDB → Supporting agent for MTD-IT
4. All enriched with names
5. 200 OK with 4 relationships

**Response**:
```json
{
  "activeClientRelationships": [
    {
      "clientId": "AB123456C",
      "clientName": "John Doe",
      "arn": "AARN1111111",
      "agentName": "Main Agent Ltd",
      "service": "HMRC-MTD-IT"
    },
    {
      "clientId": "AB123456C",
      "clientName": "John Doe",
      "arn": "AARN2222222",
      "agentName": "Supporting Agent Ltd",
      "service": "HMRC-MTD-IT"
    },
    {
      "clientId": "AB123456C",
      "clientName": "John Doe",
      "arn": "AARN1111111",
      "agentName": "Main Agent Ltd",
      "service": "HMRC-MTD-IT-SUPP"
    },
    {
      "clientId": "AB123456C",
      "clientName": "John Doe",
      "arn": "AARN3333333",
      "agentName": "IRV Agent Ltd",
      "service": "PERSONAL-INCOME-RECORD"
    }
  ]
}
```

**Frontend Action**: Display all relationships grouped by client, showing different agents and services

### 3. Client with No Relationships

**Scenario**: Client exists but has no active relationships

**Flow**:
1. Stride user requests relationships for VRN
2. Validation passes
3. HIP queried - no relationships found
4. Client details retrieved successfully
5. 200 OK with empty array for this client

**Response**:
```json
{
  "activeClientRelationships": []
}
```

**Frontend Action**: Show "No active relationships" for this client

### 4. Invalid Client Identifier in Bulk Request

**Scenario**: One client ID in batch is invalid format

**Flow**:
1. Stride user submits request with one invalid clientId format
2. Validation fails for invalid client
3. 400 Bad Request returned
4. Entire request fails (no partial success)

**Response**: 400 Bad Request

**Frontend Action**: Show validation error, ask user to correct invalid client ID

### 5. Client Details Not Found

**Scenario**: Relationships exist but client not found in DES

**Flow**:
1. Stride user requests relationships
2. Validation passes
3. Relationships retrieved successfully
4. Client details lookup fails (client not found in DES)
5. 404 Not Found returned

**Response**: 404 Not Found - ClientDetailsNotFound

**Frontend Action**: Show "Client details not available" error

### 6. Agent Details Lookup Failure

**Scenario**: Agent Maintainer unavailable or agent not found

**Flow**:
1. Stride user requests relationships
2. Validation and relationship retrieval succeed
3. Agent Maintainer fails to return agent name for one ARN
4. 500 Internal Server Error returned

**Response**: 500 Internal Server Error - ErrorRetrievingAgentDetails

**Frontend Action**: Show error message, suggest retry

### 7. All Supported Client Types

**Scenario**: Comprehensive lookup across all service types

**Flow**:
1. Stride user requests relationships for 8 clients (NINO, VRN, UTR, URN, CGTPDRef, EtmpRegistrationNumber, cbcId, PLRID)
2. All validated successfully
3. Each routed to appropriate service
4. Client names from appropriate DES endpoints
5. Agent names from Agent Maintainer
6. 200 OK with all relationships across all services

**Response**: ActiveClientsRelationshipResponse with relationships for all 8 clients covering MTD-IT, MTD-IT-SUPP, IRV, VAT, Trust, TrustNT, CGT, PPT, CBC, Pillar2

**Frontend Action**: Display comprehensive multi-service relationship view

## Error Handling

| Error Scenario | Response | Message | Note |
|----------------|----------|---------|------|
| Invalid clientIdType or clientId | 400 Bad Request | RelationshipBadRequest or TaxIdentifierError | Any validation failure |
| Stride authentication failure | 401 Unauthorized | Authentication failed | Must be Stride authenticated |
| Insufficient Stride permissions | 403 Forbidden | Forbidden | Must have required role |
| Client details not found | 404 Not Found | ClientDetailsNotFound | DES client lookup failed |
| Error retrieving client details | 500 Internal Server Error | ErrorRetrievingClientDetails | DES unavailable |
| Error retrieving agent details | 500 Internal Server Error | ErrorRetrievingAgentDetails | Agent Maintainer unavailable |
| Error retrieving relationships | 500 Internal Server Error | ErrorRetrievingRelationship | HIP/IF/Agent-Fi unavailable |
| IRV relationship not found (NINO) | 200 OK with no IRV | N/A | IRV errors recovered, doesn't fail request |
| Other errors | 500 Internal Server Error | Generic error | Unexpected failures |

## Important Notes

- ✅ VERY HIGH COMPLEXITY - Bulk endpoint supporting all client identifier types
- ✅ Stride-only endpoint - not accessible to agents or clients
- ✅ Supports batch processing of multiple clients in single request
- ✅ NINO clients get special handling - 3 parallel queries (ITSA via HIP, IRV via Agent-Fi, PartialAuth via MongoDB)
- ✅ Each relationship enriched with agent name (from Agent Maintainer) and client name (from DES)
- ✅ Supports all services: MTD-IT, MTD-IT-SUPP, IRV, VAT, Trust, TrustNT, CGT, PPT, CBC, Pillar2
- ✅ Only returns ACTIVE relationships (isActive=true)
- ✅ Client names retrieved from service-specific DES endpoints
- ✅ PartialAuth relationships included for NINO (supporting agents)
- ⚠️ Entire request fails if any single client validation fails - no partial success
- ⚠️ IRV errors are silently recovered (not included in results) - doesn't fail entire request
- ⚠️ If agent name lookup fails, entire request returns 500
- ⚠️ If client name lookup fails, returns 404 (not 500)
- ⚠️ Performance consideration: Makes multiple downstream calls per client (relationships + agent details + client details)
- ⚠️ For NINO: Up to 3 relationship sources queried in parallel + IF call for MtdItId conversion
- ⚠️ Each unique ARN requires Agent Maintainer call - could be many calls for clients with multiple agents
- ⚠️ Response can be very large if many clients with many relationships requested
- ⚠️ No pagination - all results returned in single response
- ⚠️ Different error handling for IRV (recovered) vs other services (propagated)
- ⚠️ Requires specific Stride roles: maintain_agent_relationships or maintain_agent_manually_assure

## Related Documentation

- **ACR26**: Get Stride Client Relationships - Single client version (via GET with path parameters)
- **ACR28**: Get IRV Relationships - Stride endpoint specifically for IRV relationships
- **ACR09**: Get Client Relationship Details - Agent version with comprehensive data

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
