# ACR15: Reject Authorization Request (Invitation)

## Overview

Allows a client or Stride user to reject a pending authorization request (invitation) from an agent. Uses a **similar authentication pattern to ACR13** - looks up the invitation first, validates it's pending, then authenticates that the user is authorized to reject it.

On successful rejection:
1. Updates invitation status to Rejected in MongoDB
2. Sends notification email to agent
3. Audits the rejection event
4. Returns 204 No Content

## API Details

- **API ID**: ACR15
- **Method**: PUT
- **Path**: `/client/authorisation-response/reject/{invitationId}`
- **Authentication**: Two-phase (lookup first, then validate user is client OR Stride user)
- **Audience**: internal
- **Controller**: InvitationController
- **Controller Method**: `rejectInvitation`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| invitationId | String | Yes | Unique invitation identifier (e.g., ABBBBBBBBBBBB) |

## Query Parameters

None

## Response

### Success Response (204 No Content)

No response body - invitation successfully rejected

### Error Responses

- **403 Forbidden**: Invitation not found, not pending, or user not authorized
- **401 Unauthorized**: User not authenticated
- **500 Internal Server Error**: Error parsing invitation to enrolment

## Service Architecture

See ACR15.mmd for complete sequence diagram.

### Process Flow

1. **Lookup Invitation**: Query MongoDB (no auth)
2. **Validate Status**: Check status == Pending
3. **Build Enrolment Key**: Convert invitation to enrolment key
4. **Authenticate User**: Validate user is client OR Stride user
5. **Update Status**: Set status to Rejected, update lastUpdated
6. **Send Email**: Notify agent of rejection
7. **Audit Event**: Record rejection
8. **Return 204**: No Content

## Dependencies

### Internal Services

- **InvitationService**: Orchestrates rejection
- **InvitationsRepository**: MongoDB operations
- **ValidationService**: Converts invitation to enrolment key
- **EmailService**: Sends rejection notification
- **AuditService**: Records audit event

### Database Collections

- **invitations**: UPDATE status to Rejected with new lastUpdated timestamp

## Business Logic

### Authentication Pattern

**Similar to ACR13** - unusual two-phase approach:

**Phase 1: Lookup & Validate**
- Lookup invitation (no auth)
- Check status == Pending
- If not found or not pending → 403 Forbidden

**Phase 2: Authenticate**
- Convert invitation to enrolment key
- Validate user is:
  - **Client**: Has enrolment matching clientId
  - **Stride**: Has maintain_agent_relationships role
- If not authorized → 403 Forbidden

### Status Validation

Can only reject **Pending** invitations.

Rejected for:
- Accepted invitations
- Already Rejected invitations
- Expired invitations
- Cancelled invitations

**Response**: 403 Forbidden (NoPendingInvitation) - same message as "not found"

### Rejection Process

Sequential operations (not atomic):

1. **Update MongoDB**: status = Rejected, lastUpdated = now
2. **Send Email**: Notify agent (asynchronous)
3. **Audit Event**: Record rejection with accepted=false
4. **Return**: 204 No Content

## Use Cases

### 1. Client Rejects Pending Invitation

**Request**: `PUT /client/authorisation-response/reject/ABBBBBBBBBBBB`

**User**: Client authenticated with MTD-IT enrolment

**Result**: Status updated to Rejected, email sent, audit recorded

**Response**: 204 No Content

**Frontend Action**: Show "You have successfully rejected the invitation from [Agent Name]"

### 2. Stride User Rejects on Behalf of Client

**User**: Stride user with maintain_agent_relationships role

**Result**: Same as client rejection, but audit event includes isStride=true

**Response**: 204 No Content

**Frontend Action**: Stride dashboard shows "Invitation rejected"

### 3. Client Tries to Reject Already Rejected Invitation

**Response**: 403 Forbidden (NoPendingInvitation)

**Frontend Action**: Show "This invitation cannot be rejected (not pending)"

### 4. Client Tries to Reject Expired Invitation

**Response**: 403 Forbidden (NoPendingInvitation)

**Frontend Action**: Show "This invitation has expired and cannot be rejected"

### 5. Wrong Client Tries to Reject

**Scenario**: Client A tries to reject invitation for Client B

**Response**: 403 Forbidden (NoPermissionToPerformOperation)

**Frontend Action**: Show "You are not authorized to reject this invitation"

## Error Handling

| Error | Response | Message |
|-------|----------|---------|
| Invitation not found | 403 Forbidden (NoPendingInvitation) | "Pending Invitation not found for invitationId '{id}'" |
| Status not Pending | 403 Forbidden (NoPendingInvitation) | Same as not found (security) |
| Not client OR Stride | 403 Forbidden (NoPermissionToPerformOperation) | Standard auth failure |
| Not authenticated | 401 Unauthorized | Standard |
| Can't parse to enrolment | 500 Internal Server Error | RuntimeException thrown |

## Stride User Support

Stride users with these roles can reject invitations:
- `maintain_agent_relationships`

Stride rejection is audited with `isStride=true` flag.

## Important Notes

- ⚠️ **UNUSUAL AUTH PATTERN**: Lookup before authentication (like ACR13)
- ✅ **Client OR Stride**: Both can reject
- ✅ **Pending Only**: Can only reject Pending invitations
- ✅ **MongoDB Update**: Status set to Rejected with lastUpdated timestamp
- ✅ **Email Notification**: Agent notified of rejection
- ✅ **Audit Event**: Rejection recorded with accepted=false
- ✅ **204 Response**: No Content on success (no body)
- ⚠️ **Not Atomic**: Email and audit happen after status update
- ⚠️ **Same 403**: Not found and not pending return same error (security)
- ⚠️ **Can Throw**: RuntimeException if invitation can't parse to enrolment

## Comparison with ACR16 (Accept)

| Aspect | ACR15 (Reject) | ACR16 (Accept) |
|--------|---------------|----------------|
| **Purpose** | Reject invitation | Accept and create relationship |
| **Complexity** | Low | High |
| **Status** | Rejected | Accepted |
| **Creates Relationship** | No | Yes (in EACD/ETMP) |
| **Email** | Rejection notification | Acceptance confirmation |
| **Path** | /client/authorisation-response/reject/:id | /authorisation-response/accept/:id |

## Related Documentation

- **ACR13**: Get Authorization Request Info (similar auth pattern)
- **ACR16**: Accept Authorization Request (complement to reject)
- **ACR12**: Validate Invitation for Client (before accept/reject decision)

