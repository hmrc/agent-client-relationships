# ACR21: Cancel Invitation

## Overview

Allows an agent to cancel a pending authorisation request (invitation) they have previously sent to a client. Only invitations with **Pending** status can be cancelled, and the agent must own the invitation (ARN must match). Once cancelled, the invitation cannot be accepted by the client.

## API Details

- **API ID**: ACR21
- **Method**: PUT
- **Path**: `/agent-client-relationships/agent/cancel-invitation/{invitationId}`
- **Authentication**: Agent authentication (HMRC-AS-AGENT enrolment required)
- **Audience**: internal
- **Controller**: InvitationController
- **Controller Method**: `cancelInvitation`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| invitationId | String | Yes | Unique identifier for the invitation (UUID format) |

## Query Parameters

None

## Request Body

None

## Response

### Success Response (204 No Content)

No response body. Invitation successfully cancelled.

### Error Responses

| Status | Error Code | Description |
|--------|-----------|-------------|
| 401 | Unauthorized | Agent authentication failed |
| 403 | InvalidInvitationStatus | Invitation is not in Pending status (already Accepted, Rejected, Expired, Cancelled, or Deauthorised) |
| 403 | NoPermissionOnAgency | Authenticated agent does not own this invitation (ARN mismatch) |
| 404 | InvitationNotFound | Invitation with given ID does not exist |

## Service Architecture

See ACR21.mmd for complete sequence diagram.

### Flow Summary

1. **Authentication**: Validates agent via `withAuthorisedAsAgent`
2. **Invitation Lookup**: Finds invitation by invitationId
3. **Validation**:
   - Check invitation exists (404 if not)
   - Check status is Pending (403 InvalidInvitationStatus if not)
   - Check ARN matches authenticated agent (403 NoPermissionOnAgency if not)
4. **Update**: Sets status to Cancelled and lastUpdated to current timestamp
5. **Response**: Returns 204 No Content on success

## Business Logic

### Invitation Lookup

Queries the invitations collection by invitationId only (not filtered by ARN initially). This allows the system to distinguish between "invitation not found" (404) and "invitation exists but you don't own it" (403).

### Status Validation

Only **Pending** invitations can be cancelled. If the invitation has any other status:
- **Accepted**: Client has already accepted - cannot cancel
- **Rejected**: Client has already rejected - no need to cancel
- **Expired**: Invitation has expired - cannot cancel
- **Cancelled**: Already cancelled - returns 403 (not idempotent)
- **Deauthorised**: Relationship was created and then terminated - cannot cancel
- **PartialAuth**: Partial authorisation in progress - cannot cancel

Returns 403 InvalidInvitationStatus for any non-Pending status.

### Ownership Validation

Compares the invitation's ARN with the authenticated agent's ARN. Only the agent who created the invitation can cancel it. This prevents agents from cancelling other agents' invitations.

Returns 403 NoPermissionOnAgency if ARN mismatch.

### Atomic Update

The MongoDB `updateOne` operation includes filters for:
- `arn` = authenticated agent's ARN
- `invitationId` = requested invitation ID
- `status` = Pending

This ensures the update only succeeds if **all conditions are still true** at execution time, preventing race conditions (e.g., client accepts while agent is cancelling).

### Timestamp Update

Sets `lastUpdated` field to `Instant.now()` when cancelling. This provides an audit trail of when the cancellation occurred.

## Dependencies

### External Services

None - purely database operation

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| InvitationService | cancelInvitation | Passes through to repository |
| InvitationsRepository | cancelByIdForAgent | Finds, validates, and updates invitation |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| invitations | READ/UPDATE | Finds invitation by invitationId. Validates status=Pending and arn=authenticated agent. Updates status to Cancelled and lastUpdated to now. |

## Use Cases

### 1. Agent Cancels Pending Invitation

**Scenario**: Agent sent invitation to wrong client or changed their mind

**Request**:
```
PUT /agent/cancel-invitation/A1B2C3D4E5F6
```

**Flow**:
1. Agent authenticates with HMRC-AS-AGENT
2. System finds invitation by ID
3. Validates status is Pending and ARN matches agent
4. Updates status to Cancelled
5. Client can no longer accept this invitation

**Response**: 204 No Content

**Frontend Action**: Show success message "Invitation cancelled". Remove invitation from agent's pending invitations list. Update invitation status display to "Cancelled".

### 2. Agent Tries to Cancel Already Accepted Invitation

**Scenario**: Agent attempts to cancel invitation that client has already accepted

**Request**:
```
PUT /agent/cancel-invitation/A1B2C3D4E5F6
```

**Flow**:
1. Agent authenticates
2. System finds invitation but status is Accepted
3. Returns 403 InvalidInvitationStatus

**Response**: 403 Forbidden (InvalidInvitationStatus)

**Frontend Action**: Show error message "Cannot cancel - invitation has already been accepted by the client". Invitation remains in Accepted status. Suggest using remove-authorisation endpoint instead if agent wants to terminate the relationship.

### 3. Agent Tries to Cancel Another Agent's Invitation

**Scenario**: Agent A tries to cancel invitation created by Agent B

**Request**:
```
PUT /agent/cancel-invitation/X9Y8Z7W6V5U4
```

**Flow**:
1. Agent A authenticates with ARN TARN0000001
2. System finds invitation but ARN is TARN0000002
3. Returns 403 NoPermissionOnAgency

**Response**: 403 Forbidden (NoPermissionOnAgency)

**Frontend Action**: Show error message "You do not have permission to cancel this invitation. You can only cancel invitations you have created."

### 4. Agent Tries to Cancel Non-Existent Invitation

**Scenario**: Agent provides invalid or non-existent invitation ID

**Request**:
```
PUT /agent/cancel-invitation/INVALIDID123
```

**Flow**:
1. Agent authenticates
2. System cannot find invitation with that ID
3. Returns 404 NotFound

**Response**: 404 Not Found (InvitationNotFound)

**Frontend Action**: Show error message "Invitation not found. The invitation ID may be incorrect or the invitation may have been deleted."

## Error Handling

| Error | Response | Scenario | Note |
|-------|----------|----------|------|
| InvitationNotFound | 404 | Invitation ID doesn't exist | Never existed or was deleted |
| InvalidInvitationStatus | 403 | Status is not Pending | Already processed, expired, or cancelled |
| NoPermissionOnAgency | 403 | ARN mismatch | Can only cancel own invitations |
| Unauthorized | 401 | Authentication failed | Missing or invalid agent credentials |

## Important Notes

- ✅ **Only affects Pending invitations** - cannot cancel invitations that have been Accepted, Rejected, Expired, etc.
- ✅ **Agent must own the invitation** - ARN must match authenticated agent
- ✅ **Atomic update with validation** - MongoDB filter prevents race conditions
- ✅ **Updates lastUpdated timestamp** - provides audit trail of cancellation time
- ✅ **Client can no longer accept** - cancelled invitations cannot be accepted
- ✅ **Idempotent within constraints** - cancelling already Cancelled invitation returns 403 (not 204)
- ⚠️ **Returns 403 (not 409)** for wrong status or wrong owner
- ⚠️ **Does not send email notification** to client about cancellation
- ⚠️ **Does not delete the invitation** - record remains with Cancelled status
- ⚠️ **Cannot be undone** - once cancelled, invitation cannot be reactivated (would need to create new invitation)

## Related Documentation

- **ACR18**: Get Agent Authorisation Request Info - retrieves invitation details
- **ACR19**: Track Requests - lists all invitations including cancelled ones
- **ACR20**: Create Invitation - creates invitations that can be cancelled
- **ACR22**: Reject Invitation - client's way to decline an invitation (different from cancel)

