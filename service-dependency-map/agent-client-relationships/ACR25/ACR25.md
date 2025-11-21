# ACR25: Clean Up Invitation Status - Deauthorise Accepted Invitation

## Overview

This is an administrative/cleanup endpoint that marks accepted or partial-auth invitations as deauthorised when a relationship is terminated externally. It updates the invitation status from **Accepted** or **PartialAuth** to **DeAuthorised**, sets the `relationshipEndedBy` field to 'HMRC', and updates the `lastUpdated` timestamp.

This endpoint is typically used by cleanup jobs or other backend systems when relationships are terminated through ETMP or other systems, but the invitation record needs to be updated to maintain consistency.

## API Details

- **API ID**: ACR25
- **Method**: PUT
- **Path**: `/agent-client-relationships/cleanup-invitation-status`
- **Authentication**: Standard authentication (authorised)
- **Audience**: Internal (backend cleanup/admin operations)
- **Controller**: CleanUpInvitationStatusController
- **Controller Method**: `deauthoriseInvitation`

## Path Parameters

None

## Query Parameters

None

## Request Body

```json
{
  "arn": "AARN1234567",
  "clientId": "AB123456C",
  "service": "HMRC-MTD-IT"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| arn | String | Yes | Agent Reference Number (ARN) - format: AARN1234567 |
| clientId | String | Yes | Client identifier value (e.g., NINO, MTDITID, VRN, UTR, URN, CGT reference, etc.) |
| service | String | Yes | Service identifier (e.g., HMRC-MTD-IT, HMRC-MTD-VAT, HMRC-TERS-ORG, etc.) |

## Response

### Success Response (204 No Content)

No body returned. The invitation has been successfully deauthorised.

### Error Responses

| Status Code | Description | Example Body |
|-------------|-------------|--------------|
| 400 | Bad Request - Invalid JSON or invalid clientId format | `{"code": "INVALID_CLIENT_ID", "message": "Invalid clientId \"INVALID\", for service type \"HMRC-MTD-IT\""}` |
| 401 | Unauthorized - Authentication failed | N/A |
| 404 | Not Found - No matching Accepted or PartialAuth invitation found | No body |
| 501 | Not Implemented - Unsupported service | `{"code": "UNSUPPORTED_SERVICE", "message": "Unsupported service \"INVALID-SERVICE\""}` |
| 500 | Internal Server Error | Error message |

## Service Architecture

See [ACR25.mmd](ACR25.mmd) for complete sequence diagram.

### Flow Summary

1. **Authentication**: Standard authorised() check
2. **Request Parsing**: Parse JSON body to CleanUpInvitationStatusRequest
3. **Service Validation**: Validate service is supported
4. **ClientId Validation**: Validate clientId format matches service type
5. **Database Update**: Update invitation status from Accepted/PartialAuth to DeAuthorised
6. **Response**: 204 if updated, 404 if not found, error codes for validation failures

## Business Logic

### Validation Process

The endpoint performs two validation steps before attempting the update:

1. **Service Validation**:
   - Checks if the service identifier is valid (matches `Service.forId`)
   - Returns 501 Not Implemented if service is unsupported
   
2. **ClientId Validation**:
   - Checks if the clientId format is valid for the specified service
   - Uses `ClientIdentifier` with the service's supported client ID type
   - Returns 400 Bad Request if format is invalid (e.g., invalid NINO format for MTD-IT)

### Update Operation

The MongoDB update operation:
- **Filter**: Matches invitations with:
  - `status` IN (`Accepted`, `Partialauth`)
  - `service` = provided service
  - `clientId` = provided clientId (encrypted in database)
  - `arn` = provided ARN
  
- **Update**: Sets:
  - `status` = `DeAuthorised`
  - `relationshipEndedBy` = `"HMRC"`
  - `lastUpdated` = current timestamp

### Status Filter Logic

Only invitations with specific statuses are updated:
- ✅ **Accepted** - Can be deauthorised
- ✅ **Partialauth** - Can be deauthorised
- ❌ **Pending** - Cannot be deauthorised (returns 404)
- ❌ **Cancelled** - Cannot be deauthorised (returns 404)
- ❌ **Expired** - Cannot be deauthorised (returns 404)
- ❌ **Rejected** - Cannot be deauthorised (returns 404)
- ❌ **DeAuthorised** - Already deauthorised (returns 404)

### Relationship Ended By

This field tracks who terminated the relationship:
- This endpoint always sets it to **"HMRC"**
- Indicates the relationship was terminated by HMRC systems
- Other possible values elsewhere: "Agent", "Client"

## Dependencies

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| CleanUpInvitationStatusService | validateService | Validates service identifier |
| CleanUpInvitationStatusService | validateClientId | Validates clientId format for service |
| CleanUpInvitationStatusService | deauthoriseInvitation | Orchestrates the deauthorisation |
| InvitationsRepository | deauthAcceptedInvitation | Updates MongoDB record |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| invitations | UPDATE | Updates status to DeAuthorised for Accepted/PartialAuth invitations matching arn, clientId, and service |

## Use Cases

### 1. Relationship Terminated in ETMP - Cleanup Invitation Record

**Scenario**: Relationship terminated in ETMP by HMRC, invitation record needs cleanup

**Flow**:
1. Relationship terminated in ETMP by HMRC
2. Cleanup job calls this endpoint to update invitation status
3. Invitation with status Accepted found
4. Status updated to DeAuthorised, relationshipEndedBy set to 'HMRC'
5. 204 No Content returned

**Response**:
```
Status: 204 No Content
```

**Frontend Action**: N/A - This is a backend cleanup operation

### 2. Partial Auth Invitation Cleanup

**Scenario**: Partial auth invitation exists but relationship needs cleanup

**Flow**:
1. Partial auth invitation exists but relationship needs cleanup
2. System calls this endpoint
3. Invitation with status PartialAuth found
4. Status updated to DeAuthorised
5. 204 No Content returned

**Response**:
```
Status: 204 No Content
```

**Frontend Action**: N/A - This is a backend cleanup operation

### 3. Invitation Already Deauthorised or Doesn't Exist

**Scenario**: Cleanup job attempts to deauthorise already-processed invitation

**Flow**:
1. Cleanup job calls endpoint
2. No invitation with status Accepted or PartialAuth found
3. Either invitation doesn't exist, already DeAuthorised, or has different status
4. 404 returned

**Response**:
```
Status: 404 Not Found
```

**Frontend Action**: Calling system should log but not fail - cleanup already done or not needed

### 4. Invalid Service Type Provided

**Scenario**: Cleanup job calls with invalid service identifier

**Flow**:
1. Cleanup job calls with invalid service identifier
2. Service validation fails
3. 501 Not Implemented returned

**Response**:
```json
{
  "code": "UNSUPPORTED_SERVICE",
  "message": "Unsupported service \"INVALID-SERVICE\""
}
```
```
Status: 501 Not Implemented
```

**Frontend Action**: Calling system should fix the service identifier

### 5. Invalid ClientId Format for Service

**Scenario**: ClientId format doesn't match service requirements

**Flow**:
1. Cleanup job calls with clientId that doesn't match service format
2. ClientId validation fails (e.g., invalid NINO format for MTD-IT)
3. 400 Bad Request returned

**Response**:
```json
{
  "code": "INVALID_CLIENT_ID",
  "message": "Invalid clientId \"INVALID\", for service type \"HMRC-MTD-IT\""
}
```
```
Status: 400 Bad Request
```

**Frontend Action**: Calling system should fix the clientId format

## Error Handling

| Error Scenario | Response | Message | Note |
|----------------|----------|---------|------|
| Unsupported service | 501 Not Implemented | Unsupported service "{service}" | Service must be in supported services list |
| Invalid clientId format | 400 Bad Request | Invalid clientId "{clientId}", for service type "{service}" | ClientId format must match service requirements |
| No matching invitation | 404 Not Found | No body | No invitation with status Accepted/PartialAuth matching criteria |
| Invalid JSON payload | 400 Bad Request | Invalid payload: {errors} | Request body must be valid JSON |
| Authentication failure | 401 Unauthorized | Authentication failed | Must be authenticated |
| Database error | 500 Internal Server Error | Error message | Unexpected database error |

## Important Notes

- ✅ This is a cleanup/administrative endpoint for maintaining invitation status consistency
- ✅ Only updates invitations with status 'Accepted' or 'Partialauth'
- ✅ Always sets relationshipEndedBy to 'HMRC' (not configurable)
- ✅ Idempotent for already deauthorised invitations (returns 404 but no harm done)
- ✅ Updates are atomic - all fields updated in single MongoDB operation
- ✅ ClientId is encrypted in database but provided unencrypted in request
- ⚠️ Does NOT terminate relationships in EACD or ETMP - only updates invitation record
- ⚠️ Returns 404 if invitation has wrong status (e.g., Pending, Cancelled, Expired)
- ⚠️ Service validation is strict - must be in supported services list
- ⚠️ ClientId validation is format-based - checks if format matches service type
- ⚠️ No email notifications sent - this is a silent cleanup operation
- ⚠️ This endpoint assumes the relationship has already been terminated elsewhere
- ⚠️ Not idempotent in the strict sense - second call returns 404 instead of 204
- ⚠️ Used by cleanup jobs to maintain consistency between ETMP and invitation records

## Related Documentation

- **ACR06**: Cancel Invitation - Different endpoint for cancelling pending invitations
- **ACR11**: Remove Agent Relationships - Removes relationships from EACD/ETMP
- **ACR22**: ITSA Post Signup - Can update invitation status to Accepted

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
