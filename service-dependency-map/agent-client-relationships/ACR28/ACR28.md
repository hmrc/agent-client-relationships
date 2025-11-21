# ACR28: Stride Get Client Details with Relationships

## Overview

Allows a Stride user (HMRC internal staff) to retrieve comprehensive details for a single client including client name, pending invitations (non-suspended agents only), and active main agent relationship. Supports all client identifier types. Special handling for MTD-IT includes PartialAuth check and MTD-IT-SUPP service. Pending invitations are filtered to exclude suspended agents. Client name prioritized from invitation if available, otherwise retrieved from DES. Used by HMRC staff for customer support to view client's relationship status.

## API Details

- **API ID**: ACR28
- **Method**: GET
- **Path**: `/stride/client-details/service/{service}/client/{clientIdType}/{clientId}`
- **Authentication**: Stride authentication with specific roles (maintain_agent_relationships or maintain_agent_manually_assure)
- **Audience**: Internal (HMRC staff only)
- **Controller**: StrideClientDetailsController
- **Controller Method**: `get`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| service | String | Yes | Service identifier (e.g., HMRC-MTD-IT, HMRC-MTD-VAT, HMRC-TERS-ORG, HMRC-CGT-PD, PERSONAL-INCOME-RECORD) |
| clientIdType | String | Yes | Client identifier type matching the service (e.g., 'NI' for NINO, 'VRN' for VRN, 'UTR' for UTR) |
| clientId | String | Yes | Client identifier value (must be valid format for the clientIdType) |

## Query Parameters

None

## Request Body

None

## Response

### Success Response (200 OK)

```json
{
  "clientName": "John Doe",
  "pendingInvitations": [
    {
      "clientType": "personal",
      "arn": "AARN1234567",
      "service": "HMRC-MTD-IT",
      "status": "Pending",
      "expiryDate": "2025-02-15",
      "lastUpdated": "2025-01-15T10:30:00Z",
      "invitationId": "ABCD1234567890",
      "agencyName": "XYZ Tax Services",
      "clientName": "John Doe",
      "agentIsSuspended": false,
      "isAltItsa": false
    }
  ],
  "activeMainAgent": {
    "agentName": "ABC Accountants Ltd",
    "arn": "AARN9876543",
    "service": "HMRC-MTD-IT"
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| clientName | String | Client name (from invitation or DES) |
| pendingInvitations | Array | Array of InvitationWithAgentName objects (non-suspended agents only) |
| activeMainAgent | Object | Optional ActiveMainAgent object (null if no active relationship) |

### InvitationWithAgentName

| Field | Type | Description |
|-------|------|-------------|
| clientType | String | Client type (e.g., 'personal', 'business') |
| arn | String | Agent Reference Number |
| service | String | Service identifier |
| status | String | Invitation status (typically 'Pending') |
| expiryDate | String | Invitation expiry date |
| lastUpdated | String | Last update timestamp |
| invitationId | String | Unique invitation ID |
| agencyName | String | Agent agency name |
| clientName | String | Client name |
| agentIsSuspended | Boolean | Always false (suspended agents filtered out) |
| isAltItsa | Boolean | Whether this is alternative ITSA arrangement |

### ActiveMainAgent

| Field | Type | Description |
|-------|------|-------------|
| agentName | String | Agent name from Agent Maintainer |
| arn | String | Agent Reference Number |
| service | String | Service identifier |

### Error Responses

| Status Code | Description | Example Body |
|-------------|-------------|--------------|
| 400 | Bad Request - Invalid service, clientIdType, or clientId format | Validation error message |
| 401 | Unauthorized - Stride authentication failed | N/A |
| 403 | Forbidden - Stride user does not have required role | N/A |
| 404 | Not Found - Client not found (no invitations and no client details in DES) | No body |
| 500 | Internal Server Error | Error message |

## Service Architecture

See [ACR28.mmd](ACR28.mmd) for complete sequence diagram.

### Flow Summary

1. **Validation**: Validate service, clientIdType, and clientId format
2. **Stride Authorization**: Check Stride authentication and roles
3. **Parallel Retrieval**:
   - **Pending Invitations**: Query MongoDB invitations collection, enrich with agent names, filter suspended agents
   - **Active Relationship**: Query based on service type (PartialAuth for MTD-IT, Agent-Fi for IRV, HIP for others)
4. **Client Name Resolution**: Use name from invitation if available, otherwise query DES
5. **Response**: 200 with complete details, or 404 if no client name found

## Business Logic

### Validation

Uses ValidationService.validateForEnrolmentKey to validate:
- Service identifier is supported
- ClientIdType and clientId match and are valid format
- Same validation as ACR26

### MTD-IT Service Expansion

When service is HMRC-MTD-IT, the system automatically searches for invitations for both:
- HMRC-MTD-IT
- HMRC-MTD-IT-SUPP

This ensures all relevant invitations are shown for Income Tax clients.

### Pending Invitations Retrieval

Process:
1. Query invitations collection for client and service(s)
2. For each invitation, retrieve agent record from Agent Maintainer
3. **Filter out suspended agents**: getNonSuspendedAgentRecord returns None for suspended agents
4. Build InvitationWithAgentName objects for non-suspended agents only
5. Suspended agents' invitations are excluded from response

### Active Relationship Retrieval

Different logic per service type:

#### MTD-IT
1. **First check PartialAuth**: Query partial-auth collection for main agent
2. **If PartialAuth main agent found**: Use that as active relationship (don't query HIP)
3. **If no PartialAuth**: Query HIP via IF (NINO → MtdItId → HIP)
4. This ensures correct main agent shown when PartialAuth is in use

#### IRV (PERSONAL-INCOME-RECORD)
- Query agent-fi-relationship service directly
- IRV relationships not stored in HIP
- Take first relationship if multiple exist

#### Other Services
- Query HIP directly via FindRelationshipsService
- Standard relationship lookup

#### Enrichment
If relationship found, retrieve agent name from Agent Maintainer

### Client Name Resolution

Priority:
1. **From Invitation**: If pending invitations exist and first has clientName, use that
2. **From DES**: If no invitation name, query DES via ClientDetailsService.findClientDetails
   - Different DES endpoint per identifier type:
     - NINO → getCitizenDetails + getDesignatoryDetails
     - VRN → getVrnDisplayDetails
     - UTR/URN → getTrustDetails
     - CGTPDRef → getCgtSubscriptionDetails
     - EtmpRegistrationNumber → getPptSubscriptionDetails
     - cbcId → getCbcSubscriptionDetails
     - PLRID → getPillar2SubscriptionDetails

### Not Found Condition

Returns 404 if:
- No pending invitations exist AND
- DES returns no client details

Client must be known somewhere (invitation or DES) to return 200.

### Error Handling Strategy

**Graceful degradation** - individual query failures don't fail entire request:
- **Relationship query fails**: Returns 200 with activeMainAgent = null
- **Agent Maintainer returns 404 for invitation**: That invitation filtered out
- **Agent suspended**: That invitation filtered out
- **HIP returns error**: Returns 200 with activeMainAgent = null

Only returns 404 if client name cannot be determined.

### Stride Authorization

Requires Stride authentication with one of these roles:
- `maintain_agent_relationships`
- `maintain_agent_manually_assure`

## Dependencies

### External Services

| Service | Method | Purpose | Note |
|---------|--------|---------|------|
| Agent Maintainer | GET /agent-maintainer/agent/{arn} | Retrieve agent records (name and suspension status) | Called for each invitation ARN and active relationship ARN |
| HIP (ETMP) | GET /registration/relationship | Retrieve active relationships | Not called for IRV. For MTD-IT, only called if no PartialAuth main agent |
| IF (Integration Framework) | getMtdIdFor | Convert NINO to MtdItId | Only for MTD-IT service when querying HIP |
| Agent-Fi-Relationship | GET /relationships/active | Retrieve IRV relationships | Only for PERSONAL-INCOME-RECORD service |
| DES (Multiple endpoints) | Various | Retrieve client name | Only called if no invitation with client name. Different endpoint per type |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| ValidationService | validateForEnrolmentKey | Validate service, clientIdType, clientId |
| StrideClientDetailsService | getClientDetailsWithChecks | Orchestrate retrieval of invitations, relationship, client name |
| InvitationsRepository | findAllPendingForSuppliedClient | Query pending invitations from MongoDB |
| AgentAssuranceService | getNonSuspendedAgentRecord, getAgentRecord | Retrieve and filter agent records |
| PartialAuthRepository | findMainAgent | Check for PartialAuth main agent (MTD-IT only) |
| FindRelationshipsService | getItsaRelationshipForClient, getActiveRelationshipsForClient | Retrieve active relationships |
| AgentFiRelationshipConnector | findIrvActiveRelationshipForClient | Retrieve IRV relationships |
| ClientDetailsService | findClientDetails | Route to appropriate DES service for client name |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| invitations | READ | Pending invitations for client and service. Filtered to non-suspended agents |
| partial-auth | READ | For MTD-IT only: Check if client has PartialAuth main agent |

## Use Cases

### 1. Client with Pending Invitation and Active Main Agent

**Scenario**: VAT client has pending invitation and existing main agent

**Flow**:
1. Stride user calls endpoint for VAT client
2. Validation passes, Stride authorization succeeds
3. Pending invitation found in MongoDB
4. Agent record retrieved - not suspended - included
5. Active relationship found in HIP
6. Active agent record retrieved from Agent Maintainer
7. Client name from invitation
8. 200 OK with invitation and active agent

**Response**:
```json
{
  "clientName": "CFG Solutions",
  "pendingInvitations": [
    {
      "clientType": "business",
      "arn": "AARN1234567",
      "service": "HMRC-MTD-VAT",
      "status": "Pending",
      "expiryDate": "2025-02-15",
      "lastUpdated": "2025-01-15T10:00:00Z",
      "invitationId": "VWXYZ1234567890",
      "agencyName": "XYZ Tax Services",
      "clientName": "CFG Solutions",
      "agentIsSuspended": false,
      "isAltItsa": false
    }
  ],
  "activeMainAgent": {
    "agentName": "ABC Accountants Ltd",
    "arn": "AARN9876543",
    "service": "HMRC-MTD-VAT"
  }
}
```

**Frontend Action**: Display client details with pending invitation and current agent

### 2. Client with No Invitations but Active Relationship

**Scenario**: Client has active agent but no pending invitations

**Flow**:
1. Stride user calls endpoint for VAT client
2. Validation and authorization succeed
3. No pending invitations found
4. Active relationship found in HIP
5. Active agent record retrieved
6. Client name retrieved from DES (getVrnDisplayDetails)
7. 200 OK with empty invitations array and active agent

**Response**:
```json
{
  "clientName": "CFG Solutions",
  "pendingInvitations": [],
  "activeMainAgent": {
    "agentName": "ABC Ltd",
    "arn": "AARN0000002",
    "service": "HMRC-MTD-VAT"
  }
}
```

**Frontend Action**: Display current agent relationship

### 3. Client with Pending Invitation but No Active Relationship

**Scenario**: Client has pending invitation but no current agent

**Flow**:
1. Stride user calls endpoint
2. Pending invitation found, agent not suspended
3. No active relationship found (HIP returns 422 or no relationship)
4. Client name from invitation
5. 200 OK with invitation but no active agent

**Response**:
```json
{
  "clientName": "John Smith",
  "pendingInvitations": [
    {
      "clientType": "personal",
      "arn": "AARN1111111",
      "service": "HMRC-MTD-VAT",
      "status": "Pending",
      "expiryDate": "2025-03-01",
      "lastUpdated": "2025-02-01T14:20:00Z",
      "invitationId": "PQRST9876543210",
      "agencyName": "New Agent Services",
      "clientName": "John Smith",
      "agentIsSuspended": false,
      "isAltItsa": false
    }
  ]
}
```

**Frontend Action**: Show pending invitation, no current agent

### 4. Pending Invitation from Suspended Agent - Filtered Out

**Scenario**: Invitation exists but agent is suspended

**Flow**:
1. Stride user calls endpoint
2. Pending invitation found
3. Agent Maintainer shows agent is suspended
4. getNonSuspendedAgentRecord returns None
5. Invitation filtered out
6. Active relationship found in HIP
7. 200 OK with empty invitations and active agent

**Response**:
```json
{
  "clientName": "Acme Ltd",
  "pendingInvitations": [],
  "activeMainAgent": {
    "agentName": "ABC Accountants",
    "arn": "AARN9999999",
    "service": "HMRC-MTD-VAT"
  }
}
```

**Frontend Action**: Don't show suspended agent's invitation

### 5. MTD-IT with PartialAuth Main Agent

**Scenario**: ITSA client has PartialAuth main agent

**Flow**:
1. Stride user calls with service=HMRC-MTD-IT
2. Validation passes, check for invitations for both MTD-IT and MTD-IT-SUPP
3. Check PartialAuth - main agent found
4. Use PartialAuth agent as active relationship (don't query HIP)
5. Retrieve agent name from Agent Maintainer
6. Client name from DES
7. 200 OK with PartialAuth agent as active

**Response**:
```json
{
  "clientName": "Jane Doe",
  "pendingInvitations": [],
  "activeMainAgent": {
    "agentName": "Supporting Agent Ltd",
    "arn": "AARN2222222",
    "service": "HMRC-MTD-IT"
  }
}
```

**Frontend Action**: Show PartialAuth main agent as active

### 6. IRV Service (PERSONAL-INCOME-RECORD)

**Scenario**: Query IRV relationship

**Flow**:
1. Stride user calls with service=PERSONAL-INCOME-RECORD
2. Validation and authorization succeed
3. No pending invitations (IRV doesn't use invitations typically)
4. Query agent-fi-relationship for IRV relationships
5. IRV relationship found
6. Agent name retrieved from Agent Maintainer
7. Client name from DES (getCitizenDetails)
8. 200 OK with IRV active agent

**Response**:
```json
{
  "clientName": "Robert Johnson",
  "pendingInvitations": [],
  "activeMainAgent": {
    "agentName": "IRV Specialists Ltd",
    "arn": "AARN7777777",
    "service": "PERSONAL-INCOME-RECORD"
  }
}
```

**Frontend Action**: Display IRV relationship

### 7. Client Not Found Anywhere

**Scenario**: Client has no invitations and not in DES

**Flow**:
1. Stride user calls endpoint
2. Validation and authorization succeed
3. No pending invitations
4. No active relationship
5. DES client details query returns 404
6. No client name available
7. 404 Not Found returned

**Response**: 404 Not Found (no body)

**Frontend Action**: Show "Client not found" error

### 8. Invalid Service Identifier

**Scenario**: Invalid service provided

**Flow**:
1. Stride user provides invalid service
2. Validation fails
3. 400 Bad Request returned before Stride auth check

**Response**: 400 Bad Request - Validation error

**Frontend Action**: Show validation error

## Error Handling

| Error Scenario | Response | Message | Note |
|----------------|----------|---------|------|
| Invalid service, clientIdType, or clientId | 400 Bad Request | Validation error | Validation occurs before Stride authorization |
| Stride authentication failure | 401 Unauthorized | Authentication failed | Must be Stride authenticated |
| Insufficient Stride permissions | 403 Forbidden | Forbidden | Must have required Stride role |
| No invitations and client not found in DES | 404 Not Found | No body | Client must be known somewhere |
| Active relationship query fails | 200 OK with activeMainAgent = null | N/A | Doesn't fail entire request |
| Agent Maintainer returns 404 for invitation | 200 OK with that invitation filtered out | N/A | Invalid invitation excluded |
| Agent suspended for invitation | 200 OK with that invitation filtered out | N/A | Suspended agent's invitation excluded |
| HIP returns error for relationship | 200 OK with activeMainAgent = null | N/A | HIP errors don't fail request |

## Important Notes

- ✅ Stride-only endpoint - not accessible to agents or clients
- ✅ Returns comprehensive client view: name, pending invitations, and active agent
- ✅ Pending invitations ONLY include non-suspended agents
- ✅ For MTD-IT, automatically queries both MTD-IT and MTD-IT-SUPP invitations
- ✅ For MTD-IT relationships, PartialAuth main agent takes precedence over HIP
- ✅ Client name prioritized from invitation, falls back to DES
- ✅ IRV service queries agent-fi-relationship, not HIP
- ✅ Different DES endpoints per client identifier type
- ✅ Returns 200 with empty invitations/null agent if those queries fail - doesn't fail entire request
- ⚠️ Returns 404 ONLY if client name cannot be determined (no invitations AND DES returns no details)
- ⚠️ Suspended agents' invitations are silently filtered out - not included in response
- ⚠️ Invitations where agent record not found are filtered out
- ⚠️ Active relationship query failures result in activeMainAgent = null, not 404 or 500
- ⚠️ HIP errors (422, etc.) don't fail the request - just no active agent shown
- ⚠️ Validation occurs BEFORE Stride authorization (efficient rejection)
- ⚠️ For MTD-IT: If PartialAuth main agent exists, HIP is NOT queried
- ⚠️ Each invitation ARN requires Agent Maintainer call - could be slow with many invitations
- ⚠️ Active relationship ARN also requires Agent Maintainer call
- ⚠️ Single client only - for bulk use ACR27
- ⚠️ Requires specific Stride roles: maintain_agent_relationships or maintain_agent_manually_assure

## Related Documentation

- **ACR27**: Stride Get Active Relationships (Bulk) - Bulk version for multiple clients
- **ACR26**: Get Stride Client Relationships - Stride endpoint for single service/client relationship query
- **ACR09**: Get Client Relationship Details - Agent version with comprehensive data

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
