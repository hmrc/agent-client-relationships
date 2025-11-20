# ACR32: API Get Invitation

## Overview

Allows an external authenticated system to retrieve details of a specific invitation by its ID. Returns full invitation details including the agent's unique ID (uid) and normalized agency name. Validates that the ARN in the request matches the invitation's agent ARN for security.

## API Details

- **API ID**: ACR32
- **Method**: GET
- **Path**: `/api/{arn}/invitation/{invitationId}`
- **Authentication**: OAuth2 (external system authentication)
- **Audience**: external
- **Controller**: ApiGetInvitationController
- **Controller Method**: `getInvitation`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `arn` | Arn | Yes | Agent Reference Number - must match the invitation's agent ARN |
| `invitationId` | String | Yes | Unique invitation identifier |

## Query Parameters

None

## Request Body

None

## Response

### Success Response (200 OK)

Returns invitation details with agent information.

```json
{
  "uid": "AB12CD34",
  "normalizedAgentName": "acme-tax-agency",
  "created": "2024-01-15T10:30:00Z",
  "service": "HMRC-MTD-IT",
  "status": "Pending",
  "expiresOn": "2024-02-14",
  "invitationId": "ABERULMHCKKW3",
  "lastUpdated": "2024-01-15T10:30:00Z"
}
```

**Response Fields**:
- `uid`: 8-character unique identifier for the agent
- `normalizedAgentName`: Normalized version of the agency name
- `created`: ISO 8601 timestamp when invitation was created
- `service`: Service type (e.g., HMRC-MTD-IT, HMRC-MTD-VAT)
- `status`: Invitation status (Pending, Accepted, Rejected, Expired, Cancelled, Partialauth)
- `expiresOn`: ISO 8601 date when invitation expires
- `invitationId`: Unique invitation identifier
- `lastUpdated`: ISO 8601 timestamp when invitation was last updated

### Error Responses

| Status | Code | Description |
|--------|------|-------------|
| 401 | Unauthorized | OAuth2 authentication failed |
| 422 | AGENT_SUSPENDED | Agent is suspended |
| 422 | INVITATION_NOT_FOUND | Invitation doesn't exist |
| 422 | NO_PERMISSION_ON_AGENCY | ARN doesn't match invitation's agent |
| 422 | UNSUPPORTED_SERVICE | Service not in API supported list |
| 500 | INTERNAL_SERVER_ERROR | Internal server error |

## Service Architecture

See ACR32.mmd for complete sequence diagram.

**Flow Summary**:
1. OAuth2 authentication check
2. Check if agent is suspended via AgentAssuranceService
3. Normalize agent name from agent record
4. Get or create agent reference record with uid
5. Retrieve invitation from invitations collection
6. Validate ARN matches invitation's agent
7. Validate service is supported
8. Return invitation with uid and normalized agent name

## Business Logic

### Agent Suspension Check

Calls `AgentAssuranceService.getNonSuspendedAgentRecord(arn)` to verify the agent is not suspended. Returns `AGENT_SUSPENDED` error if the agent is suspended.

### ARN Validation

Security check to ensure the ARN in the request matches the invitation's agent ARN. Compares `invitation.arn` with the requested `arn` parameter. Returns `NO_PERMISSION_ON_AGENCY` if they don't match.

### Service Support Check

Validates that the invitation's service is supported by the API. Checks if `invitation.service` is in the `apiSupportedServices` configuration list. Returns `UNSUPPORTED_SERVICE` if not supported.

### Agent Reference Record Lookup

Gets or creates an agent reference record with a unique ID (uid). Looks up by ARN in the `agent-references` collection. If not found, creates a new record with an 8-character secure random uid.

## Dependencies

### External Services

| Service | Purpose | Method | Note |
|---------|---------|--------|------|
| **Agent Assurance** | Get agent details and verify not suspended | `GET /agent-assurance/agent/{arn}` | Must not be suspended |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| **AgentAssuranceService** | `getNonSuspendedAgentRecord` | Fetches agent record, returns None if suspended |
| **InvitationLinkService** | `normaliseAgentName` | Normalizes agent agency name |
| **InvitationLinkService** | `getAgentReferenceRecordByArn` | Gets or creates agent reference record with uid |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| `invitations` | READ | Retrieve invitation by invitationId |
| `agent-references` | READ/INSERT | Find agent reference record by ARN, create if not exists |

## Use Cases

### 1. External System Retrieves Pending Invitation

**Scenario**: External system wants to check the status of an invitation

**Flow**:
1. External system authenticates with OAuth2
2. Makes GET request with ARN and invitationId
3. System verifies agent is not suspended
4. System retrieves invitation from database
5. System validates ARN matches invitation's agent
6. System validates service is supported
7. System gets or creates agent reference record
8. Returns invitation with uid and normalized agent name

**Response**:
```json
{
  "uid": "AB12CD34",
  "normalizedAgentName": "acme-tax-agency",
  "created": "2024-01-15T10:30:00Z",
  "service": "HMRC-MTD-IT",
  "status": "Pending",
  "expiresOn": "2024-02-14",
  "invitationId": "ABERULMHCKKW3",
  "lastUpdated": "2024-01-15T10:30:00Z"
}
```

**Frontend Action**: Display invitation details to user

### 2. External System Tries to Retrieve Invitation for Wrong Agent

**Scenario**: External system provides an ARN that doesn't match the invitation's agent

**Flow**:
1. External system authenticates with OAuth2
2. Makes GET request with ARN and invitationId
3. System verifies agent is not suspended
4. System retrieves invitation from database
5. System detects ARN mismatch
6. Returns NO_PERMISSION_ON_AGENCY error

**Response**:
```json
{
  "code": "NO_PERMISSION_ON_AGENCY"
}
```

**Frontend Action**: Display error - user doesn't have permission to view this invitation

## Error Handling

| Scenario | Response | Message | Note |
|----------|----------|---------|------|
| Agent is suspended | 422 | AGENT_SUSPENDED | Prevents suspended agents from accessing invitation details |
| Invitation not found | 422 | INVITATION_NOT_FOUND | InvitationId doesn't exist in database |
| ARN mismatch | 422 | NO_PERMISSION_ON_AGENCY | Security check - requested ARN doesn't match invitation's agent ARN |
| Service not supported | 422 | UNSUPPORTED_SERVICE | Invitation's service is not in the API supported services list |
| OAuth2 authentication fails | 401 | Unauthorized | External system authentication required |

## Important Notes

- ✅ Returns full invitation details including agent uid and normalized name
- ✅ Security validation - ARN must match invitation's agent ARN
- ✅ Only returns invitations for non-suspended agents
- ✅ Automatically creates agent reference record if it doesn't exist
- ✅ Only supports services in the apiSupportedServices configuration
- ⚠️ All business errors return 422 Unprocessable Entity (not 404)
- ⚠️ Agent reference record uses 8-character secure random uid
- ⚠️ Normalized agent name is computed from the agent's agency name
- ⚠️ Returns invitation regardless of status (Pending, Accepted, Rejected, etc.)

## Related Documentation

- **ACR31**: API Create Invitation - creates invitations that can be retrieved with this endpoint
- **ACR33**: API Get Invitations - retrieves multiple invitations for an agent

