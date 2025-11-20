# ACR18: Get Agent Authorisation Request Info

## Overview

Retrieves details about a specific authorisation request (invitation) for an agent, including a shareable invitation link. This endpoint returns the full invitation details along with a generated link (UID and normalized agent name) that the agent can share with clients for onboarding.

## API Details

- **API ID**: ACR18
- **Method**: GET
- **Path**: `/agent-client-relationships/agent/{arn}/authorisation-request-info/{invitationId}`
- **Authentication**: Agent authentication (HMRC-AS-AGENT enrolment required)
- **Audience**: internal
- **Controller**: AuthorisationRequestInfoController
- **Controller Method**: `get`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| arn | String | Yes | Agent Reference Number - must match authenticated agent |
| invitationId | String | Yes | Unique identifier for the invitation (UUID format) |

## Query Parameters

None

## Response

### Success Response (200 OK)

Returns `AuthorisationRequestInfo` containing the invitation and shareable link.

```json
{
  "authorisationRequest": {
    "invitationId": "A1B2C3D4E5F6",
    "arn": "TARN0000001",
    "service": "HMRC-MTD-IT",
    "clientId": "ABCDE1234567890",
    "clientIdType": "MTDITID",
    "suppliedClientId": "AB123456C",
    "suppliedClientIdType": "NI",
    "clientName": "John Smith",
    "agencyName": "Test Agency Ltd",
    "agencyEmail": "agent@test-agency.com",
    "warningEmailSent": false,
    "expiredEmailSent": false,
    "status": "Pending",
    "relationshipEndedBy": null,
    "clientType": "personal",
    "expiryDate": "2025-12-17",
    "created": "2025-11-17T10:00:00Z",
    "lastUpdated": "2025-11-17T10:00:00Z"
  },
  "agentLink": {
    "uid": "ABC123XYZ789",
    "normalizedAgentName": "test-agency-ltd"
  }
}
```

### Error Responses

| Status | Description | Note |
|--------|-------------|------|
| 401 | Unauthorized | Agent authentication failed - missing or invalid credentials |
| 403 | Forbidden | Authenticated agent ARN doesn't match path ARN |
| 404 | Not Found | No invitation found with given ID for this agent (could mean invitation doesn't exist or belongs to different agent) |

## Service Architecture

See ACR18.mmd for complete sequence diagram.

### Flow Summary

1. **Authentication**: Validates agent via `withAuthorisedAsAgent`
2. **Invitation Lookup**: Queries invitations collection by ARN and invitation ID
3. **Link Generation**: 
   - Retrieves agent record from Agent Assurance to get current agency name
   - Normalizes agency name for URL usage
   - Looks up or creates agent-reference record with unique UID
   - Updates normalized names list if needed
4. **Response**: Returns invitation details with shareable link

## Business Logic

### Invitation Lookup

Queries the invitations collection with both ARN and invitation ID to ensure the invitation belongs to the requesting agent. This prevents agents from accessing other agents' invitations.

### Link Generation

The endpoint creates or retrieves a shareable invitation link:

1. Gets current agent record from Agent Assurance
2. Normalizes agency name by removing special characters, converting to lowercase, and replacing spaces with hyphens
3. Looks up existing agent-reference record by ARN
4. If agent reference exists:
   - Checks if normalized name is in the names array
   - Updates array if current name not present (handles agency name changes)
5. If agent reference doesn't exist:
   - Generates new unique UID using codetable (ABCDEFGHJKLMNOPRSTUWXYZ123456789)
   - Creates new agent-reference record
6. Returns UID and normalized name for use in shareable links

### Agent Name Normalization

Multiple normalized agent names can be associated with one agent reference to handle agency name changes over time. The normalized name is stored for future link validation when clients use the link.

## Dependencies

### External Services

| Service | Method | Purpose |
|---------|--------|---------|
| Agent Assurance | getAgentRecord | Retrieves agent details including current agency name for link generation |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| InvitationService | findInvitationForAgent | Retrieves invitation from database by ARN and invitation ID |
| InvitationLinkService | createLink | Creates or retrieves shareable invitation link for the agent |
| AgentAssuranceService | getAgentRecord | Wrapper for Agent Assurance calls |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| invitations | READ | Queries invitation by arn and invitationId |
| agent-reference | READ/INSERT/UPDATE | Retrieves existing record by ARN, creates if not exists, updates normalisedAgentNames array if current name not present |

## Use Cases

### 1. Agent Views Invitation Details

**Scenario**: Agent wants to view details of an invitation they created

**Flow**:
1. Agent authenticates and makes GET request with their ARN and invitation ID
2. System retrieves invitation from database
3. System generates or retrieves shareable link for the agent
4. Returns full invitation details with link

**Response**: 200 OK with AuthorisationRequestInfo

**Frontend Action**: Display invitation details (client name, service, status, expiry date, etc.) and provide shareable link UI element for agent to copy/share

### 2. Agent Gets Shareable Link

**Scenario**: Agent wants to get a shareable link to send to client

**Flow**:
1. Agent requests invitation details
2. System generates link with UID and normalized agent name
3. Link can be shared with client for them to accept invitation

**Response**: 200 OK with link details in agentLink field

**Frontend Action**: Display shareable link URL (e.g., `/invitations/{uid}/{normalized-agent-name}`) for agent to copy and send to client via email, SMS, or other channel

## Error Handling

| Error | Response | Note |
|-------|----------|------|
| Invitation not found for agent | 404 Not Found | Could mean invitation doesn't exist or belongs to different agent (does not reveal existence) |
| Agent not authenticated | 401 Unauthorized | Missing or invalid agent credentials |
| ARN mismatch | 403 Forbidden | Authenticated agent ARN doesn't match path ARN |
| Database error | 500 Internal Server Error | Unexpected database failure |

## Important Notes

- ✅ Returns both invitation details and shareable link in single call
- ✅ Agent-reference records have no TTL - they persist indefinitely
- ✅ Multiple normalized agent names can exist per agent reference (to handle agency name changes)
- ✅ The UID is unique per ARN and used in shareable invitation links
- ⚠️ Only returns invitations belonging to the authenticated agent (enforces agent isolation)
- ⚠️ Returns 404 if invitation not found OR if it belongs to different agent (does not reveal existence)
- ⚠️ Link generation queries Agent Assurance to get current agency name
- ⚠️ If agent-reference record doesn't exist, creates one with generated UID
- ⚠️ The normalized agent name is stored for future link validation when clients use the link

## Related Documentation

- **ACR19**: Create Invitation - creates the invitations that this endpoint retrieves
- **ACR22**: Cancel Invitation - cancels invitations
- **ACR23**: Track Requests - lists all invitations for an agent

