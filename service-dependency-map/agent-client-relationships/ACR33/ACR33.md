# ACR33: API Get Invitations

## Overview

Allows an external authenticated system to retrieve all invitations for a specific agent ARN. Returns a bulk response containing the agent's uid, normalized agency name, and a list of all invitations filtered by API-supported services. Similar to ACR32 but returns multiple invitations instead of a single one.

## API Details

- **API ID**: ACR33
- **Method**: GET
- **Path**: `/api/{arn}/invitations`
- **Authentication**: OAuth2 (external system authentication)
- **Audience**: external
- **Controller**: ApiGetInvitationsController
- **Controller Method**: `getInvitations`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `arn` | Arn | Yes | Agent Reference Number - all invitations for this agent will be returned |

## Query Parameters

None

## Request Body

None

## Response

### Success Response (200 OK)

Returns bulk invitations response with agent information.

```json
{
  "uid": "AB12CD34",
  "normalizedAgentName": "acme-tax-agency",
  "invitations": [
    {
      "created": "2024-01-15T10:30:00Z",
      "service": "HMRC-MTD-IT",
      "status": "Pending",
      "expiresOn": "2024-02-14",
      "invitationId": "ABERULMHCKKW3",
      "lastUpdated": "2024-01-15T10:30:00Z"
    },
    {
      "created": "2024-01-16T14:20:00Z",
      "service": "HMRC-MTD-VAT",
      "status": "Accepted",
      "expiresOn": "2024-02-15",
      "invitationId": "CDEFULMHCKKW4",
      "lastUpdated": "2024-01-17T09:15:00Z"
    }
  ]
}
```

**Response Fields**:
- `uid`: 8-character unique identifier for the agent
- `normalizedAgentName`: Normalized version of the agency name
- `invitations`: Array of invitation objects with the following fields:
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
| 500 | INTERNAL_SERVER_ERROR | Internal server error |

## Service Architecture

See ACR33.mmd for complete sequence diagram.

**Flow Summary**:
1. OAuth2 authentication check
2. Check if agent is suspended via AgentAssuranceService
3. Normalize agent name from agent record
4. Get or create agent reference record with uid
5. Query all invitations for ARN filtered by API-supported services
6. Return bulk response with agent info and invitation list

## Business Logic

### Agent Suspension Check

Calls `AgentAssuranceService.getNonSuspendedAgentRecord(arn)` to verify the agent is not suspended. Returns `AGENT_SUSPENDED` error if the agent is suspended.

### Service Support Check

Only returns invitations for API-supported services. The repository query filters invitations by the `apiSupportedServices` list configured in application.conf.

### Agent Reference Record Lookup

Gets or creates an agent reference record with a unique ID (uid). Looks up by ARN in the `agent-references` collection. If not found, creates a new record with an 8-character secure random uid.

### Bulk Retrieval

Returns all invitations for the agent, not just one. Calls `findAllForAgentService` which queries MongoDB for all invitations matching the ARN and supported services.

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
| `invitations` | READ | Find all invitations for ARN filtered by API-supported services |
| `agent-references` | READ/INSERT | Find agent reference record by ARN, create if not exists |

## Use Cases

### 1. External System Retrieves All Invitations for an Agent

**Scenario**: External system wants to get all invitations for an agent

**Flow**:
1. External system authenticates with OAuth2
2. Makes GET request with ARN
3. System verifies agent is not suspended
4. System gets or creates agent reference record
5. System queries all invitations for ARN filtered by API-supported services
6. Returns bulk response with agent info and invitation list

**Response**:
```json
{
  "uid": "AB12CD34",
  "normalizedAgentName": "acme-tax-agency",
  "invitations": [
    {
      "created": "2024-01-15T10:30:00Z",
      "service": "HMRC-MTD-IT",
      "status": "Pending",
      "expiresOn": "2024-02-14",
      "invitationId": "ABERULMHCKKW3",
      "lastUpdated": "2024-01-15T10:30:00Z"
    }
  ]
}
```

**Frontend Action**: Display list of invitations to user

### 2. Agent Has No Invitations

**Scenario**: External system requests invitations for an agent with none

**Flow**:
1. External system authenticates with OAuth2
2. Makes GET request with ARN
3. System verifies agent is not suspended
4. System gets or creates agent reference record
5. System queries invitations - finds none
6. Returns successful response with empty invitations array

**Response**:
```json
{
  "uid": "AB12CD34",
  "normalizedAgentName": "acme-tax-agency",
  "invitations": []
}
```

**Frontend Action**: Display 'No invitations' message to user

## Error Handling

| Scenario | Response | Message | Note |
|----------|----------|---------|------|
| Agent is suspended | 422 | AGENT_SUSPENDED | Prevents suspended agents from accessing invitations |
| OAuth2 authentication fails | 401 | Unauthorized | External system authentication required |
| No invitations found | 200 | Empty invitations array | Returns successful response with empty array, not an error |

## Important Notes

- ✅ Returns ALL invitations for the agent (bulk retrieval)
- ✅ Similar to ACR32 but retrieves multiple invitations instead of one
- ✅ Automatically filters by API-supported services only
- ✅ Returns agent uid and normalized name with the invitations
- ✅ Only returns invitations for non-suspended agents
- ✅ Automatically creates agent reference record if it doesn't exist
- ✅ Returns 200 OK with empty array if no invitations found (not an error)
- ⚠️ No query parameters for filtering - returns ALL invitations for the ARN
- ⚠️ Does not include clientId or individual invitation filtering
- ⚠️ Agent reference record uses 8-character secure random uid
- ⚠️ Normalized agent name is computed from the agent's agency name
- ⚠️ Invitations include all statuses (Pending, Accepted, Rejected, etc.)

## Related Documentation

- **ACR32**: API Get Invitation - retrieves a single invitation by ID
- **ACR31**: API Create Invitation - creates invitations that can be retrieved with this endpoint

