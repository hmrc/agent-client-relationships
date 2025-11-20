# ACR10: Validate Agent Invitation Link

## Overview

Validates an agent's invitation link using a unique identifier (UID) and normalized agent name. This is a **public endpoint** (no authentication required) used when clients click on agent-shared invitation links.

The endpoint performs three-step validation:
1. Verify the UID exists in the agent-reference collection
2. Verify the normalized agent name matches
3. Verify the agent is not suspended

This two-factor approach (UID + name) provides security against link guessing while maintaining simplicity for clients.

## API Details

- **API ID**: ACR10
- **Method**: GET
- **Path**: `/agent/agent-reference/uid/{uid}/{normalizedAgentName}`
- **Authentication**: **None** - public endpoint
- **Audience**: internal
- **Controller**: InvitationLinkController
- **Controller Method**: `validateLink`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| uid | String | Yes | 8-character unique identifier for the agent's invitation link |
| normalizedAgentName | String | Yes | Normalized agent name (lowercase, spaces→hyphens, special chars removed) |

## Query Parameters

None

## Response

### Success Response (200 OK)

Returns ValidateLinkResponse with agent details:

```json
{
  "arn": "TARN0000001",
  "agencyName": "ABC Accountants Ltd"
}
```

### Error Responses

- **404 Not Found**: UID not found OR normalized agent name does not match
- **403 Forbidden**: Agent is suspended

**Note**: Both "UID not found" and "name mismatch" return 404 for security - don't reveal if UID is valid.

## Service Architecture

### Service Layer Components

1. **InvitationLinkController (ILC)**: Handles public request, maps result to HTTP response
2. **InvitationLinkService (ILS)**: Orchestrates validation steps
3. **AgentReferenceRepository (ARR)**: Queries MongoDB for agent reference record
4. **agent-assurance (AAS)**: Checks agent suspension status

## Interaction Flow

See ACR10.mmd for complete sequence diagram.

### Validation Steps

1. **Lookup by UID**: Query agent-reference collection
2. **Validate Name**: Check if normalizedAgentName is in record's normalisedAgentNames array
3. **Check Suspension**: Call agent-assurance to verify agent not suspended
4. **Extract Name**: Return agent's current agency name

## Dependencies

### External Services

- **agent-assurance**: Checks agent suspension status and provides agent details
  - Method: `getNonSuspendedAgentRecord(arn)`
  - Returns: None if suspended, Some(AgentDetailsDesResponse) if active

### Database Collections

- **agent-reference**:
  - **Fields**: uid (unique), arn (unique), normalisedAgentNames (array)
  - **Indexes**: uid, arn
  - **TTL**: None - records persist indefinitely
  - **Encryption**: normalisedAgentNames are AES encrypted

## Business Logic

### Name Normalization

Agent names are normalized to create URL-safe strings:

```
Input:  "ABC Accountants Ltd"
Step 1: Lowercase → "abc accountants ltd"
Step 2: Spaces → hyphens → "abc-accountants-ltd"
Step 3: Remove special chars → "abc-accountants-ltd"
Output: "abc-accountants-ltd"
```

### Multiple Normalized Names

An agent can have multiple normalized names in their record:

**Reason**: Agency name may change over time  
**Behavior**: Old links continue to work  
**Storage**: Array `normalisedAgentNames` in MongoDB  
**Example**: `["abc-accountants-ltd", "abc-accountancy-services-ltd"]`

### UID Generation

UIDs are 8-character random strings generated when agent creates their link (separate `createLink` endpoint).

**Method**: `RandomStringUtils.secure().next(8, codetable)`  
**Character set**: Lowercase alphanumeric  
**Example**: `abc12345`

## Error Handling

| Error | Condition | Response | Log Message |
|-------|-----------|----------|-------------|
| AgentReferenceDataNotFound | UID not in collection | 404 Not Found | "Agent Reference Record not found for uid: {uid}" |
| NormalizedAgentNameNotMatched | Name not in normalisedAgentNames | 404 Not Found | "Agent Reference Record not found for uid: {uid}" |
| AgentSuspended | agent-assurance returns None | 403 Forbidden | "Agent is suspended for uid: {uid}" |

**Security Note**: Both UID not found and name mismatch return the same 404 response and log message - prevents revealing if a UID is valid.

## Use Cases

### 1. Client Clicks Valid Invitation Link

**URL**: `https://tax.service.gov.uk/invite?uid=abc12345&agent=abc-accountants-ltd`

**Flow**:
1. Frontend extracts uid and normalizedAgentName from URL
2. Calls ACR10 with both parameters
3. Receives agent details
4. Displays: "ABC Accountants Ltd wants to act as your agent"

**Response**: 200 OK

### 2. Client Tries Invalid Link

**URL**: `https://tax.service.gov.uk/invite?uid=invalid99&agent=some-agent`

**Response**: 404 Not Found

**Frontend Action**: Show "Invalid or expired invitation link"

### 3. Client Tries Link with Wrong Name

**URL**: `https://tax.service.gov.uk/invite?uid=abc12345&agent=wrong-name`

**Response**: 404 Not Found

**Security**: Requires knowledge of agent name to access link

### 4. Client Tries Link for Suspended Agent

**URL**: `https://tax.service.gov.uk/invite?uid=abc12345&agent=abc-accountants-ltd`

**Response**: 403 Forbidden

**Frontend Action**: Show "This agent is currently suspended"

### 5. Agent Changes Agency Name

**Scenario**: Agent's agency name changes from "ABC Accountants Ltd" to "ABC Accountancy Services Ltd"

**Behavior**:
- Old links with `abc-accountants-ltd` still work
- New links use `abc-accountancy-services-ltd`
- Record contains both in normalisedAgentNames array

## Security Considerations

### Two-Factor Validation

Requires both UID and normalized agent name:
- **Prevents link guessing**: Can't just iterate through UIDs
- **URL-safe**: Normalized names work in URLs without encoding
- **Client-friendly**: No authentication required for convenience

### Consistent Error Responses

Both "UID not found" and "name mismatch" return 404 with same log message:
- **Security**: Don't reveal if a UID is valid
- **Privacy**: Don't expose which agents have links

### Encrypted Storage

- normalisedAgentNames are AES encrypted in MongoDB
- Protection at rest for agent data

### Suspension Check

Always checks agent suspension status before returning details - prevents suspended agents from receiving new clients via links.

## Important Notes

- ✅ **Public Endpoint**: No authentication required - accessible to any client
- ✅ **Two-Factor Security**: Both UID and name must match
- ✅ **8-Character UIDs**: Random lowercase alphanumeric
- ✅ **Multiple Names Supported**: For agency name changes
- ✅ **Always Checks Suspension**: Prevents access to suspended agents
- ✅ **No TTL**: Links persist indefinitely (static agent links)
- ✅ **Encrypted Names**: AES encryption in MongoDB
- ✅ **Consistent 404s**: Don't reveal UID validity for security
- ⚠️ **Name Normalization Required**: Frontend must normalize before calling
- ⚠️ **Array of Names**: Agent can have multiple normalized names

## Related Endpoints

### createLink (ACR10b)

- **Purpose**: Agent creates/retrieves their invitation link
- **Authentication**: Agent authentication required
- **Generates**: UID and normalized name
- **Note**: Different endpoint, same controller

### validateInvitationForClient (ACR10c)

- **Purpose**: Client validates invitation with full checks
- **Authentication**: Client authentication required
- **Includes**: Invitation status, existing relationship checks
- **Note**: More comprehensive than validateLink

## Database Schema

### agent-reference Collection

```json
{
  "uid": "abc12345",
  "arn": "TARN0000001",
  "normalisedAgentNames": [
    "abc-accountants-ltd",
    "abc-accountancy-services-ltd"
  ]
}
```

**Indexes**:
- `uid` (unique)
- `arn` (unique)

**No TTL**: Records persist indefinitely for static agent invitation links
