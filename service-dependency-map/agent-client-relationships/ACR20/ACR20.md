# ACR20: Remove Agent Authorisation

## Overview

⚠️ **HIGH COMPLEXITY ENDPOINT** - Removes/terminates an existing authorisation between an agent and client. This endpoint handles **three distinct flows** based on service type:

1. **PIR (Personal Income Record)**: Delegates to agent-fi-relationship service
2. **Alt-ITSA (MTD-IT/MTD-IT-SUPP with NINO)**: Deauthorises partial auth and updates invitations
3. **All other services**: Performs full relationship deletion including EACD enrolment deallocation and ETMP relationship removal

Uses a recovery mechanism with delete-record tracking for resilience. **Not atomic** - can partially succeed if EACD or ETMP operations fail.

## API Details

- **API ID**: ACR20
- **Method**: POST
- **Path**: `/agent-client-relationships/agent/{arn}/remove-authorisation`
- **Authentication**: Flexible - supports agent (HMRC-AS-AGENT), client (matching client ID), or Stride roles
- **Audience**: internal
- **Controller**: RemoveAuthorisationController
- **Controller Method**: `removeAuthorisation`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| arn | String | Yes | Agent Reference Number |

## Query Parameters

None

## Request Body

```json
{
  "clientId": "AB123456C",
  "service": "HMRC-MTD-IT"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| clientId | String | Yes | Client identifier (NINO, MTDITID, VRN, UTR, URN, CGT reference, PLR ID, etc.) |
| service | String | Yes | Service code (HMRC-MTD-IT, HMRC-MTD-VAT, PERSONAL-INCOME-RECORD, etc.) |

## Response

### Success Response (204 No Content)

No response body. Authorisation successfully removed.

### Error Responses

| Status | Error Code | Description |
|--------|-----------|-------------|
| 400 | UnsupportedService | Service code not in supported services list |
| 400 | InvalidClientId | Client ID format invalid for service type |
| 400 | ClientRegistrationNotFound | Alt-ITSA: NINO to MTDITID lookup failed (client not registered for MTD) |
| 401 | Unauthorized | Authentication failed |
| 403 | Forbidden | Not authorized to remove this relationship |
| 404 | RelationshipNotFound | Relationship does not exist in EACD or ETMP |
| 423 | RelationshipDeletionInProgress | Deletion already in progress - retry later |
| 500 | RelationshipDeleteFailed | EACD or ETMP deletion failed |

## Service Architecture

See ACR20.mmd for complete sequence diagram with three distinct service flows.

### Flow Summary

**Validation Phase** (All Services):
1. Parse and validate JSON request body
2. Validate service code is supported
3. Validate client ID format for service type
4. Construct enrolment key
5. For alt-ITSA: Convert NINO to MTDITID via IF/HIP if needed
6. Authenticate user (agent/client/stride)

**Execution Phase** (Service-Specific):

**PIR Flow**:
1. Call agent-fi-relationship DELETE endpoint
2. Update local invitations (set relationshipEndedBy, status=Deauthorised)
3. Send PIR audit event

**Alt-ITSA Flow** (MTD-IT/MTD-IT-SUPP with NINO):
1. Deauthorise partial-auth (set expiryDate to now)
2. Update PartialAuth invitations to Deauthorised status
3. Send partial auth audit event

**Standard Flow** (All Other Services):
1. Check for existing delete-record (recovery mode)
2. Acquire recovery lock
3. Get agent's principal group ID from EACD
4. Create delete-record for tracking
5. Deallocate enrolment from agent group in EACD (with sync status tracking)
6. Delete relationship in ETMP (with sync status tracking)
7. Remove delete-record
8. Update invitations to Deauthorised
9. Send termination audit event

## Business Logic

### Service Type Routing

The endpoint routes to three completely different flows:

**PIR (Personal Income Record)**:
- Uses separate agent-fi-relationship microservice
- Does NOT call EACD or ETMP directly
- Only updates local invitations database

**Alt-ITSA (MTD-IT/MTD-IT-SUPP with NINO)**:
- Detected when service is MTD-IT/MTD-IT-SUPP AND client ID is NINO format
- Deauthorises partial-auth relationship (invitation-based, not full relationship)
- Does NOT call EACD or ETMP
- Updates partial-auth and invitations collections only

**Standard Services**:
- All other services (MTD-VAT, CGT, Trusts, CBC, Pillar2, etc.)
- Full relationship deletion across EACD, ETMP, and invitations
- Uses recovery mechanism for resilience

### Recovery Mechanism

Provides resilience against partial failures:

1. **Delete-Record Tracking**: Creates record at start of deletion with arn, enrolmentKey, and sync statuses
2. **Sync Status**: Tracks EACD (ES) and ETMP separately (InProgress/Success/Failed)
3. **Recovery Lock**: Uses MongoLockService to prevent concurrent operations on same relationship
4. **Resume Logic**: If delete-record exists, resumes from incomplete step rather than starting over
5. **Cleanup**: Delete-record removed only after successful completion

### Enrolment Key Replacement

For alt-ITSA services with NINO:
- Calls IF/HIP `getMtdIdFor(nino)` to get MTDITID
- Replaces enrolment key identifier with MTDITID if found
- Falls back to NINO if MTDITID not found
- Ensures correct enrolment key for EACD operations

### Authentication Flexibility

Supports three authentication types via `authorisedUser`:

1. **Agent**: HMRC-AS-AGENT enrolment with matching ARN
2. **Client**: Client authentication with matching client ID
3. **Stride**: Roles `maintain_agent_relationships` or `maintain_agent_manually_assure`

### Relationship Ended By Tracking

Determines who initiated termination from affinity group:
- **Agent** affinity → "Agent"
- **Individual/Organisation** affinity → "Client"  
- **None/Stride** → "HMRC"

Stored in invitation `relationshipEndedBy` field for audit trail.

## Dependencies

### External Services

| Service | Methods | Purpose | Services Using |
|---------|---------|---------|----------------|
| EACD | getPrincipalGroupIdFor, deallocateEnrolmentFromAgent | Get agent's group ID and remove enrolment allocation | Standard services only |
| HIP/IF (ETMP) | deleteAgentRelationship, getMtdIdFor | Delete relationship in tax platform, convert NINO to MTDITID | Standard services (deleteAgentRelationship), Alt-ITSA (getMtdIdFor) |
| Agent FI Relationship | deleteRelationship | Handle PIR relationship deletion | PIR service only |

### Internal Services

| Service | Methods | Purpose |
|---------|---------|---------|
| RemoveAuthorisationService | validateRequest, deauthPartialAuth, deauthAltItsaInvitation, replaceEnrolmentKeyForItsa | Validate request and handle alt-ITSA logic |
| ValidationService | validateForEnrolmentKey | Validate client ID and construct enrolment key |
| DeleteRelationshipsService | deleteRelationship, setRelationshipEnded | Orchestrate relationship deletion with recovery |
| AuditService | auditForPirTermination, sendTerminatePartialAuthAuditEvent, sendTerminateRelationshipAuditEvent | Send service-specific audit events |

### Database Collections

| Collection | Operations | Description |
|------------|------------|-------------|
| invitations | UPDATE | All flows: Sets relationshipEndedBy and status=Deauthorised. Alt-ITSA: specifically targets PartialAuth status invitations |
| partial-auth | UPDATE | Alt-ITSA only: Sets expiryDate to current time to deauthorise |
| delete-record | INSERT/READ/UPDATE/DELETE | Standard services only: Tracks deletion progress. Created at start, updated during EACD/ETMP ops, deleted on completion |

## Use Cases

### 1. Agent Terminates Relationship (Standard Service)

**Scenario**: Agent wants to end their authorisation for a client's MTD VAT

**Request**:
```json
POST /agent/TARN0000001/remove-authorisation
{
  "clientId": "123456789",
  "service": "HMRC-MTD-VAT"
}
```

**Flow**:
1. Agent authenticates with ARN TARN0000001
2. System validates VRN format
3. Creates delete-record for tracking
4. Gets agent's group ID from EACD
5. Deallocates VAT enrolment from group in EACD
6. Removes relationship from ETMP
7. Updates invitation to Deauthorised
8. Removes delete-record
9. Sends audit event

**Response**: 204 No Content

**Frontend Action**: Show success message "Authorisation removed". Remove client from agent's client list.

### 2. Client Terminates Relationship

**Scenario**: Client wants to remove agent's access to their tax information

**Request**:
```json
POST /agent/TARN0000001/remove-authorisation
{
  "clientId": "AB123456C",
  "service": "HMRC-MTD-IT"
}
```

**Flow**:
1. Client authenticates with NINO AB123456C
2. System performs deletion (same as agent-initiated)
3. Sets relationshipEndedBy to "Client"

**Response**: 204 No Content

**Frontend Action**: Show success message "Agent access removed". Agent no longer appears in client's list of authorised agents.

### 3. Agent Terminates Alt-ITSA Partial Auth

**Scenario**: Agent wants to end partial authorisation for client's MTD IT

**Request**:
```json
POST /agent/TARN0000001/remove-authorisation
{
  "clientId": "AB123456C",
  "service": "HMRC-MTD-IT"
}
```

**Flow**:
1. Agent authenticates
2. System detects alt-ITSA (NINO + MTD-IT)
3. Deauthorises partial-auth (expiryDate = now)
4. Updates PartialAuth invitations to Deauthorised
5. Sends partial auth audit event

**Response**: 204 No Content

**Frontend Action**: Show "Partial authorisation removed". Note: Full authorisation (if exists) is unaffected.

### 4. HMRC Stride User Terminates Relationship

**Scenario**: HMRC staff needs to manually terminate a relationship

**Request**:
```json
POST /agent/TARN0000001/remove-authorisation
{
  "clientId": "123456789",
  "service": "HMRC-MTD-VAT"
}
```

**Flow**:
1. Stride user authenticates with maintain_agent_relationships role
2. System performs standard deletion
3. Sets relationshipEndedBy to "HMRC"

**Response**: 204 No Content

**Frontend Action**: Show "Relationship terminated by HMRC".

### 5. Deletion Fails Midway - Recovery on Retry

**Scenario**: ETMP call times out during deletion, then agent retries

**Initial Attempt**:
1. Delete-record created
2. EACD deallocation succeeds (sync status: Success)
3. ETMP deletion times out (sync status: Failed)
4. Delete-record persists
5. Returns 500 error

**Retry Attempt**:
1. Finds existing delete-record
2. Resumes from ETMP step (skips EACD)
3. ETMP deletion succeeds
4. Completes deletion
5. Removes delete-record

**Response**: 204 No Content

**Frontend Action**: Initial error message "Deletion failed". Retry succeeds with "Authorisation removed".

## Error Handling

| Error | Response | Scenario | Note |
|-------|----------|----------|------|
| UnsupportedService | 400 | Service code not in supported list | Check service code spelling |
| InvalidClientId | 400 | Client ID format invalid for service | E.g., invalid NINO format |
| ClientRegistrationNotFound | 400 | Alt-ITSA NINO→MTDITID lookup failed | Client not registered for MTD |
| RelationshipNotFound | 404 | Not found in EACD AND ETMP | Both systems returned false/empty |
| RelationshipDeletionInProgress | 423 | Delete-record exists | Another process deleting - retry later |
| RelationshipDeleteFailed | 500 | EACD or ETMP call failed | Partial deletion may have occurred |

## Important Notes

- ⚠️ **HIGH COMPLEXITY** - Three completely different flows based on service type (PIR/alt-ITSA/standard)
- ⚠️ **NOT ATOMIC** - EACD and ETMP operations can partially succeed (recovery mechanism handles this)
- ⚠️ **Returns 423 Locked** if deletion already in progress (not 409 Conflict)
- ⚠️ **Returns 404** if relationship not found in BOTH EACD and ETMP (doesn't reveal which)
- ⚠️ **PIR service** delegates to agent-fi-relationship microservice (separate system)
- ⚠️ **Alt-ITSA** only updates partial-auth and invitations (no EACD/ETMP calls)
- ⚠️ **Standard flow** deallocates from EACD and removes from ETMP
- ⚠️ **Flexible authentication** - supports agent, client, AND Stride
- ⚠️ **Alt-ITSA converts** NINO to MTDITID via IF/HIP for enrolment key
- ✅ Tracks who ended relationship (Agent/Client/HMRC) in invitations
- ✅ Recovery lock prevents concurrent deletions of same relationship
- ✅ Delete-record sync status tracks EACD and ETMP separately
- ✅ Sends different audit events based on service type
- ✅ All flows update invitations collection to Deauthorised status

## Related Documentation

- **ACR04**: Create Relationship - inverse operation (creates relationships)
- **ACR09**: Get Active Relationships for Client - shows active relationships before termination
- **ACR10**: Get Inactive Relationships for Client - terminated relationships appear here
- **ACR15**: Accept Invitation - creates relationship that this endpoint removes

