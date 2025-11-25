# ACR12: Validate Invitation for Client

## Overview

Validates an agent's invitation for an authenticated client by verifying the UID, checking agent suspension status, finding matching invitations, and determining if the client already has an existing main agent for the service. This is the second step in the invitation flow after ACR10 (validate link).

Used when a client clicks an invitation link and needs to see full invitation details including whether accepting would replace an existing agent.

## API Details

- **API ID**: ACR12
- **Method**: POST
- **Path**: `/client/validate-invitation`
- **Authentication**: Client authentication via Government Gateway (must have enrolments for requested services)
- **Audience**: internal
- **Controller**: InvitationLinkController
- **Controller Method**: `validateInvitationForClient`

## Path Parameters

None

## Query Parameters

None

## Request Body

```json
{
  "uid": "abc12345",
  "serviceKeys": ["HMRC-MTD-IT", "HMRC-MTD-VAT"]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| uid | String | Yes | 8-character agent invitation link UID |
| serviceKeys | Array[String] | Yes | Service keys the client wants to check (must match client's enrolments) |

## Response

### Success Response (200 OK)

Returns ValidateInvitationResponse with invitation details:

```json
{
  "invitationId": "ABBBBBBBBBBBB",
  "service": "HMRC-MTD-IT",
  "agencyName": "ABC Accountants Ltd",
  "status": "Pending",
  "lastUpdated": "2025-11-15T10:30:00Z",
  "existingMainAgent": {
    "agentName": "XYZ Tax Services",
    "isThisAgent": false
  },
  "clientType": "personal"
}
```

**Fields**:
- `invitationId`: Unique invitation ID
- `service`: Service identifier
- `agencyName`: Inviting agent's agency name
- `status`: Pending, Accepted, Rejected, Expired, or Cancelled
- `lastUpdated`: Timestamp of last status change
- `existingMainAgent`: Optional - present if client already has an agent
  - `agentName`: Existing agent's name
  - `isThisAgent`: true if existing agent is same as inviting agent
- `clientType`: "personal" or "business"

### Error Responses

- **404 Not Found**: UID not found OR no matching invitations found
- **403 Forbidden**: Agent suspended OR client not enrolled for requested services
- **401 Unauthorized**: Client not authenticated

## Service Architecture

See ACR12.mmd for complete sequence diagram.

### Key Components

1. **InvitationLinkService**: Validates UID, checks agent suspension
2. **InvitationService/Repository**: Queries MongoDB for matching invitations
3. **CheckRelationshipsService**: Checks for existing main agent
4. **EACD**: Queries for existing agent delegations
5. **agent-fi-relationship**: Checks PIR relationships
6. **agent-assurance**: Provides agent details

## Business Logic

### Service Mapping

Special handling for HMRC-NI and HMRC-PT enrolments:

```
If serviceKeys contains HMRC-MTD-IT:
  HMRC-NI or HMRC-PT → HMRC-MTD-IT
Else:
  HMRC-NI or HMRC-PT → PERSONAL-INCOME-RECORD
```

**Multi-Agent Services**: MTD-IT also searches for MTD-IT-SUPP invitations

### Invitation Selection

When multiple invitations found:
1. **Priority 1**: Return Pending invitation (if exists)
2. **Priority 2**: Return most recently created invitation (sorted by created timestamp descending)

### Existing Main Agent Check

Different logic per service:

| Service | Check Method |
|---------|--------------|
| MTD-IT, MTD-IT-SUPP | findMainAgentForNino - checks EACD, partial_auth, mappings |
| PIR | agent-fi-relationship findIrvActiveRelationshipForClient |
| CBC-ORG | Uses provided enrolment to check EACD delegations |
| Others | Builds enrolment key from invitation, checks EACD delegations |

**isThisAgent Flag**: Compares existing agent ARN with inviting agent ARN

## Dependencies

### External Services

- **agent-assurance**: Agent suspension check and agent details
- **EACD (Enrolment Store Proxy)**: Queries for existing agent delegations
- **agent-fi-relationship**: PIR relationship checks

### Database Collections

- **agent-reference**: UID lookup
- **invitations**: Query for matching invitations

## Use Cases

### 1. Client with Pending Invitation, No Existing Agent

**Request**:
```json
{
  "uid": "abc12345",
  "serviceKeys": ["HMRC-MTD-IT"]
}
```

**Response**:
```json
{
  "invitationId": "ABBBBBBBBBBBB",
  "service": "HMRC-MTD-IT",
  "agencyName": "ABC Accountants Ltd",
  "status": "Pending",
  "lastUpdated": "2025-11-15T10:30:00Z",
  "existingMainAgent": null,
  "clientType": "personal"
}
```

**Frontend Action**: Show invitation details, allow accept/reject

### 2. Client Already Has Different Agent

**Response**:
```json
{
  "invitationId": "CCCCCCCCCCCCC",
  "service": "HMRC-MTD-VAT",
  "agencyName": "New Agent Ltd",
  "status": "Pending",
  "lastUpdated": "2025-11-15T14:00:00Z",
  "existingMainAgent": {
    "agentName": "Current Agent Ltd",
    "isThisAgent": false
  },
  "clientType": "business"
}
```

**Frontend Action**: Show warning - "You already have Current Agent Ltd. Accepting will replace them with New Agent Ltd."

### 3. Same Agent (Re-invitation)

**Response**:
```json
{
  "existingMainAgent": {
    "agentName": "ABC Accountants Ltd",
    "isThisAgent": true
  }
}
```

**Frontend Action**: Show "You already have this agent authorized for this service"

### 4. Expired Invitation

**Response**:
```json
{
  "status": "Expired",
  "lastUpdated": "2025-10-15T10:30:00Z"
}
```

**Frontend Action**: Show "This invitation has expired. Please ask the agent to send a new invitation."

### 5. No Invitations Found

**Response**: 404 Not Found

**Frontend Action**: Show "Invalid invitation or no invitations found for your services"

### 6. Agent Suspended

**Response**: 403 Forbidden

**Frontend Action**: Show "This agent is currently suspended"

## Error Handling

| Error | Response | Frontend Action |
|-------|----------|-----------------|
| UID not found | 404 Not Found | "Invalid invitation link" |
| Agent suspended | 403 Forbidden | "Agent currently suspended" |
| No invitations found | 404 Not Found | "No invitations found for your services" |
| Not enrolled | 403 Forbidden | "You are not enrolled for the requested services" |
| Not authenticated | 401 Unauthorized | Redirect to login |

## Comparison with ACR10

| Aspect | ACR10 | ACR12 |
|--------|-------|-------|
| **Purpose** | Validate link | Validate invitation for client |
| **Auth** | None (public) | Client (with enrolments) |
| **Input** | UID + name (path) | UID + serviceKeys (body) |
| **Output** | Agent details | Full invitation details |
| **Checks** | Link valid, agent not suspended | + Invitations exist, existing relationships |
| **Sequence** | Step 1 | Step 2 |

## Important Notes

- ✅ **Client Auth Required**: Must have enrolments for requested services
- ✅ **Request Body**: POST with JSON body (not query params)
- ✅ **Service Mapping**: Handles HMRC-NI/PT special cases
- ✅ **Invitation Priority**: Pending first, else most recent
- ✅ **Existing Agent Check**: Different logic per service
- ✅ **isThisAgent Flag**: Indicates if existing agent is same as inviting
- ✅ **Multi-Agent**: MTD-IT checks MTD-IT-SUPP too
- ⚠️ **serviceKeys Must Match**: Client must be enrolled for requested services
- ⚠️ **404 if No Invitations**: Even if UID valid, returns 404 if no matching invitations

## Related Documentation

- **ACR10**: Validate Agent Invitation Link (step 1)
- **ACR13**: Accept Invitation (next step after validation)
- **ACR14**: Reject Invitation (alternative next step)

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
