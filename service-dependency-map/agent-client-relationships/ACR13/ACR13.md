# ACR13: Get Authorization Request Info for Client

## Overview

Retrieves basic information about a specific authorization request (invitation) for a client or agent. This endpoint has an **unusual authentication pattern** - it first looks up the invitation without authentication, then validates that the user is either the agent who sent the invitation OR the client who received it.

Returns minimal information: agent name, service, and invitation status.

## API Details

- **API ID**: ACR13
- **Method**: GET
- **Path**: `/client/authorisation-request-info/{invitationId}`
- **Authentication**: Two-phase (lookup first, then validate user is agent OR client)
- **Audience**: internal
- **Controller**: AuthorisationRequestInfoController
- **Controller Method**: `getForClient`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| invitationId | String | Yes | Unique invitation identifier (e.g., ABBBBBBBBBBBB) |

## Query Parameters

None

## Response

### Success Response (200 OK)

Returns AuthorisationRequestInfoForClient with basic invitation details:

```json
{
  "agentName": "ABC Accountants Ltd",
  "service": "HMRC-MTD-IT",
  "status": "Pending"
}
```

**Fields**:
- `agentName`: Agent's agency name (from agent-assurance)
- `service`: Service identifier
- `status`: Pending, Accepted, Rejected, Expired, or Cancelled

### Error Responses

- **404 Not Found**: Invitation not found
- **403 Forbidden**: User not authorized (not the agent OR the client)
- **401 Unauthorized**: User not authenticated

## Service Architecture

See ACR13.mmd for complete sequence diagram.

### Key Components

1. **InvitationService**: Queries MongoDB for invitation
2. **InvitationsRepository**: MongoDB operations
3. **agent-assurance**: Provides agent details

## Business Logic

### Unusual Authentication Pattern

**Phase 1: Lookup without auth**
- Invitation is queried from MongoDB WITHOUT authentication
- Allows checking if invitation exists first

**Phase 2: Validate user**
- After invitation found, validates user is authorized
- User must be EITHER:
  - **Agent**: Has ARN matching invitation.arn
  - **Client**: Has tax identifier matching invitation.clientId

**Why this pattern?**
- Allows distinguishing between "invitation not found" (404) and "not authorized" (403)
- Both agent and client can view the same invitation

### Authorized Users

| User Type | Validation |
|-----------|------------|
| Agent | Has HMRC-AS-AGENT enrolment with ARN matching invitation.arn |
| Client | Has enrolment with tax identifier matching invitation.clientId |
| Stride | Technically supported but strideRoles=[] means not used here |

### Agent Details

Always retrieves fresh agent details using `getAgentRecord`:
- **Note**: Uses getAgentRecord (not getNonSuspendedAgentRecord)
- Returns details **even if agent is suspended**

## Dependencies

### External Services

- **agent-assurance**: Provides agent details (agency name)

### Database Collections

- **invitations**: Queries for invitation by invitationId

## Use Cases

### 1. Client Views Pending Invitation

**Request**: `GET /client/authorisation-request-info/ABBBBBBBBBBBB`

**User**: Client authenticated with MTD-IT enrolment

**Response**:
```json
{
  "agentName": "ABC Accountants Ltd",
  "service": "HMRC-MTD-IT",
  "status": "Pending"
}
```

**Frontend Action**: Display "ABC Accountants Ltd has invited you for Making Tax Digital for Income Tax. Status: Pending"

### 2. Agent Views Invitation They Sent

**Request**: `GET /client/authorisation-request-info/CCCCCCCCCCCCC`

**User**: Agent authenticated with matching ARN

**Response**:
```json
{
  "agentName": "XYZ Tax Services",
  "service": "HMRC-MTD-VAT",
  "status": "Accepted"
}
```

**Frontend Action**: Agent dashboard shows "Invitation to client for VAT - Status: Accepted"

### 3. Client Views Expired Invitation

**Response**:
```json
{
  "agentName": "Old Agent Ltd",
  "service": "HMRC-CGT-PD",
  "status": "Expired"
}
```

**Frontend Action**: Show "This invitation from Old Agent Ltd has expired"

### 4. User Not Authorized

**Scenario**: User tries to view invitation for different client

**Response**: 403 Forbidden

**Frontend Action**: Show "You are not authorized to view this invitation"

### 5. Invalid Invitation ID

**Response**: 404 Not Found

**Frontend Action**: Show "Invitation not found"

## Error Handling

| Error | Response | Scenario |
|-------|----------|----------|
| Invitation not found | 404 Not Found | Invalid invitation ID |
| Not agent OR client | 403 Forbidden | User not authorized for this invitation |
| Not authenticated | 401 Unauthorized | No valid session |

## Comparison with ACR12

| Aspect | ACR12 (validateInvitationForClient) | ACR13 (getForClient) |
|--------|-----------------------------------|---------------------|
| **Purpose** | Validate invitation with full checks | Get basic invitation info |
| **Auth** | Client only (with service keys) | Agent OR Client |
| **Auth Timing** | Before lookup | After lookup |
| **Response** | Full details + existing agent check | Minimal: name, service, status |
| **Complexity** | High (checks EACD, agent-fi-relationship) | Low (simple lookup) |
| **Use Case** | Client about to accept/reject | View invitation status |

## Important Notes

- ⚠️ **UNUSUAL AUTH PATTERN**: Lookup happens before authentication (rare pattern)
- ✅ **Flexible Access**: Both agent AND client can view same invitation
- ✅ **Agent View**: Agent can see invitations they sent
- ✅ **Client View**: Client can see invitations they received
- ✅ **Works When Suspended**: Uses getAgentRecord (not getNonSuspendedAgentRecord)
- ✅ **Minimal Response**: Only agent name, service, status
- ✅ **All Statuses**: Returns info for ANY status (Pending, Accepted, Rejected, Expired, Cancelled)
- ⚠️ **Stride Not Really Supported**: strideRoles=[] means Stride users can't actually use this
- ⚠️ **No Validation**: Doesn't check if client has enrolments for service

## Related Documentation

- **ACR12**: Validate Invitation for Client (more comprehensive, client-only)
- **ACR14**: Reject Invitation
- **ACR15**: Accept Invitation (actual acceptance, not just info retrieval)

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
