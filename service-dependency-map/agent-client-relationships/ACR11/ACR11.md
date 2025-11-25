# ACR11: Create/Get Agent Invitation Link

## Overview

Creates or retrieves an agent's invitation link by generating/fetching a unique identifier (UID) and normalized agent name. This is an **idempotent operation** - the first call creates a new record with a generated UID, and subsequent calls return the existing UID.

When an agent's agency name changes, the new normalized name is added to the record's array while preserving old names, ensuring old invitation links continue to work.

## API Details

- **API ID**: ACR11
- **Method**: GET
- **Path**: `/agent/agent-link`
- **Authentication**: Agent authentication via `withAuthorisedAsAgent`
- **Audience**: internal
- **Controller**: InvitationLinkController
- **Controller Method**: `createLink`

## Path Parameters

None

## Query Parameters

None

## Response

### Success Response (200 OK)

Returns CreateLinkResponse with UID and normalized agent name:

```json
{
  "uid": "abc12345",
  "normalizedAgentName": "abc-accountants-ltd"
}
```

### Error Responses

- **401 Unauthorized**: Agent not authenticated
- **500 Internal Server Error**: Error retrieving agent record or database error

## Service Architecture

### Service Layer Components

1. **InvitationLinkController (ILC)**: Authenticates agent, returns response
2. **InvitationLinkService (ILS)**: Orchestrates get details, normalize name, find/create/update record
3. **agent-assurance (AAS)**: Provides current agent details
4. **AgentReferenceRepository (ARR)**: MongoDB operations

## Interaction Flow

See ACR11.mmd for complete sequence diagram.

### Operation Flow

1. **Get Agent Details**: Retrieve current agency name from agent-assurance
2. **Normalize Name**: Convert agency name to URL-safe format
3. **Lookup by ARN**: Check if agent reference record exists
4. **Branch**:
   - **If not exists**: Generate UID, create new record
   - **If exists**: Check if current normalized name in array, add if not
5. **Return**: UID and current normalized name

## Dependencies

### External Services

- **agent-assurance**: Provides current agent details including agency name
  - Method: `getAgentRecord(arn)`
  - Note: Uses getAgentRecord (not getNonSuspendedAgentRecord) - returns details even if agent suspended

### Database Collections

- **agent-reference**:
  - **Operations**: READ, INSERT, UPDATE
  - **Fields**: uid (unique), arn (unique), normalisedAgentNames (array)
  - **Indexes**: uid, arn
  - **TTL**: None - records persist indefinitely
  - **Encryption**: normalisedAgentNames are AES encrypted

## Business Logic

### Idempotency

**First Call** (no record exists):
1. Generate new UID using `RandomStringUtils.secure().next(8, codetable)`
2. Create record: `{uid: "abc12345", arn: "TARN0000001", normalisedAgentNames: ["abc-accountants-ltd"]}`
3. Return UID and normalized name

**Subsequent Calls** (record exists):
1. Look up existing record by ARN
2. Check if current normalized name in `normalisedAgentNames` array
3. If not in array: update with `$addToSet` (adds new name, keeps old)
4. Return existing UID and current normalized name

### Name Normalization

Agency names are normalized to create URL-safe strings:

```
Input:  "ABC Accountants Ltd"
Step 1: Lowercase → "abc accountants ltd"
Step 2: Spaces → hyphens → "abc-accountants-ltd"
Step 3: Remove special chars → "abc-accountants-ltd"
Output: "abc-accountants-ltd"
```

**Implementation**:
```scala
def normaliseAgentName(agentName: String) = agentName
  .toLowerCase()
  .replaceAll("\\s+", "-")
  .replaceAll("[^A-Za-z0-9-]", "")
```

### UID Generation

UIDs are generated only on first call when no record exists:

- **Method**: `RandomStringUtils.secure().next(8, codetable)`
- **Character Set**: Lowercase alphanumeric
- **Length**: 8 characters
- **Example**: `abc12345`

### Name Array Update

When agency name changes, the new normalized name is added to the array using MongoDB's `$addToSet`:

**Before**:
```json
{
  "uid": "abc12345",
  "arn": "TARN0000001",
  "normalisedAgentNames": ["abc-accountants-ltd"]
}
```

**After name change**:
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

**Benefit**: Old links with old name continue to work, new links use new name.

## Use Cases

### 1. Agent Creates Invitation Link for First Time

**Scenario**: Agent has never created an invitation link before

**Flow**:
1. Agent calls ACR11
2. No record exists for agent's ARN
3. System generates new UID: `abc12345`
4. Creates record with UID and normalized name
5. Returns UID and normalized name

**Response**:
```json
{
  "uid": "abc12345",
  "normalizedAgentName": "abc-accountants-ltd"
}
```

**Frontend Action**: Display link to agent:
```
Your invitation link:
https://tax.service.gov.uk/invite?uid=abc12345&agent=abc-accountants-ltd

[Copy Link Button]
```

### 2. Agent Retrieves Existing Invitation Link

**Scenario**: Agent has created link before, no agency name change

**Flow**:
1. Agent calls ACR11
2. Record exists with current normalized name in array
3. Returns existing UID and current normalized name

**Response**:
```json
{
  "uid": "abc12345",
  "normalizedAgentName": "abc-accountants-ltd"
}
```

**Note**: Same UID as before - idempotent operation

### 3. Agent's Agency Name Has Changed

**Scenario**: Agent's agency name changed from "ABC Accountants Ltd" to "ABC Accountancy Services Ltd"

**Flow**:
1. Agent calls ACR11
2. Record exists but new normalized name not in array
3. Adds new name to array: `["abc-accountants-ltd", "abc-accountancy-services-ltd"]`
4. Returns existing UID with new normalized name

**Response**:
```json
{
  "uid": "abc12345",
  "normalizedAgentName": "abc-accountancy-services-ltd"
}
```

**Frontend Action**: Display new link to agent, but old links still work:
```
Your invitation link:
https://tax.service.gov.uk/invite?uid=abc12345&agent=abc-accountancy-services-ltd

Note: Old links with your previous agency name still work
```

### 4. Suspended Agent Creates Link

**Scenario**: Agent is suspended but needs to retrieve their link information

**Flow**:
1. Agent calls ACR11
2. agent-assurance returns details (even though suspended)
3. Returns UID and normalized name

**Note**: Unlike ACR10 (which returns 403 for suspended agents), ACR11 works even if agent suspended - allows agent to access their link data.

## Error Handling

| Scenario | Response | Note |
|----------|----------|------|
| Agent not authenticated | 401 Unauthorized | Authentication required |
| Error from agent-assurance | 500 Internal Server Error | Exception propagated |
| Database error | 500 Internal Server Error | Create/update failure propagated |

## Comparison with ACR10

| Aspect | ACR10 (validateLink) | ACR11 (createLink) |
|--------|---------------------|-------------------|
| **Purpose** | Client validates agent link | Agent creates/retrieves link |
| **Authentication** | None (public endpoint) | Agent authentication required |
| **Operation** | Read-only lookup | Create/Update record |
| **Suspension Check** | Returns 403 if suspended | Works even if suspended |
| **UID** | Receives as input | Generates on first call |
| **Name Check** | Validates name matches | Updates name array if changed |
| **Relationship** | Validates links created by ACR11 | Creates links that ACR10 validates |

## Important Notes

- ✅ **Idempotent**: Safe to call multiple times - returns same UID
- ✅ **Agent Authentication Required**: Unlike ACR10 which is public
- ✅ **UID Generated Once**: Only on first call when record doesn't exist
- ✅ **Name Array Updates**: Agency name changes add to array, don't replace
- ✅ **Old Links Work**: Previous invitation links remain valid after name change
- ✅ **Works When Suspended**: Uses getAgentRecord (not getNonSuspendedAgentRecord)
- ✅ **No TTL**: Records persist indefinitely
- ✅ **Encrypted Storage**: normalisedAgentNames encrypted in MongoDB
- ✅ **Unique ARN**: One record per agent (ARN has unique index)
- ⚠️ **Returns Current Name**: Response has current normalized name, not all names in array
- ⚠️ **No Duplicates**: $addToSet ensures no duplicate names in array

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

## Related Documentation

- **ACR10**: Validate Agent Invitation Link (client-side validation of links created here)

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
