# ACR23: Update Trust Invitation - Replace URN with UTR

## Overview

This endpoint updates a trust-related invitation by replacing the temporary URN (Unique Reference Number) with the permanent UTR (Unique Taxpayer Reference) after trust registration completes. It is called by the **Trusts Enrolment Orchestrator** when a trust transitions from non-taxable to taxable status.

The endpoint performs a service-level transition from **HMRC-TERSNT-ORG** (non-taxable trust) to **HMRC-TERS-ORG** (taxable trust), updating both the service identifier and the client ID type from URN to UTR.

## API Details

- **API ID**: ACR23
- **Method**: POST
- **Path**: `/agent-client-relationships/invitations/trusts-enrolment-orchestrator/{urn}/update`
- **Authentication**: None (internal endpoint)
- **Audience**: Internal (called by Trusts Enrolment Orchestrator)
- **Controller**: InvitationController
- **Controller Method**: `replaceUrnWithUtr`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| urn | String | Yes | Unique Reference Number (URN) - temporary identifier for non-taxable trusts (format: XXTRUST12345678) |

## Query Parameters

None

## Request Body

```json
{
  "utr": "1234567890"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| utr | String | Yes | Unique Taxpayer Reference (UTR) - permanent 10-digit identifier for taxable trusts |

## Response

### Success Response (204 No Content)

No body returned. The invitation has been successfully updated from URN to UTR.

### Error Responses

| Status Code | Description |
|-------------|-------------|
| 400 | Bad Request - Invalid JSON in request body or missing 'utr' field |
| 404 | Not Found - No invitation found with the specified URN for service HMRC-TERSNT-ORG |
| 500 | Internal Server Error |

## Service Architecture

See [ACR23.mmd](ACR23.mmd) for complete sequence diagram.

### Flow Summary

1. **Request Processing**: Controller extracts UTR from JSON request body
2. **Service Call**: InvitationService.updateInvitation() called with:
   - Old service: HMRC-TERSNT-ORG (TrustNT)
   - Old identifier: URN with type URN
   - New service: HMRC-TERS-ORG (Trust)
   - New identifier: UTR with type SAUTR
3. **Database Update**: MongoDB updateOne() operation finds invitation by old service/URN and updates to new service/UTR
4. **Response**: 204 if one record updated, 404 if no records found

## Business Logic

### Service Transition

When a trust completes registration and receives a permanent UTR, the invitation record must be updated to reflect the new taxable status. This involves:

- **Service Update**: HMRC-TERSNT-ORG → HMRC-TERS-ORG
- **Client ID Type Update**: URN → SAUTR
- **Client ID Value Update**: URN value → UTR value
- **Timestamp Update**: lastUpdated set to current time

### Database Update Operation

The MongoDB update uses specific matching criteria:
- Filter: `service=HMRC-TERSNT-ORG AND clientId=urn AND clientIdType=URN`
- Updates: service, clientId, clientIdType, suppliedClientId, suppliedClientIdType, lastUpdated

All fields are updated atomically in a single operation.

### Return Logic

The endpoint determines success based on MongoDB's modified count:
- Modified count = 1 → 204 No Content (success)
- Modified count = 0 → 404 Not Found (invitation doesn't exist)

## Dependencies

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| InvitationService | updateInvitation | Orchestrates the update operation |
| InvitationsRepository | updateInvitation | Performs the MongoDB update |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| invitations | UPDATE | Updates invitation record from HMRC-TERSNT-ORG/URN to HMRC-TERS-ORG/UTR |

## Use Cases

### 1. Trust Registration Completes - URN to UTR Transition

**Scenario**: A trust completes registration and receives a permanent UTR

**Flow**:
1. Trusts Enrolment Orchestrator completes trust registration
2. New UTR is assigned to the previously non-taxable trust
3. Orchestrator calls this endpoint with the URN and new UTR
4. Invitation record is updated from HMRC-TERSNT-ORG/URN to HMRC-TERS-ORG/UTR
5. Future invitation acceptance will use the permanent UTR

**Response**:
```
Status: 204 No Content
```

**Frontend Action**: N/A - This is a backend-to-backend call

### 2. Attempt to Update Non-Existent Invitation

**Scenario**: Orchestrator calls with a URN that doesn't have an associated invitation

**Flow**:
1. Trusts Enrolment Orchestrator calls with URN that doesn't exist in invitations collection
2. Database query finds no matching invitation
3. 404 returned

**Response**:
```
Status: 404 Not Found
```

**Frontend Action**: Orchestrator should log the error and potentially raise an alert

## Error Handling

| Error Scenario | Response | Message | Note |
|----------------|----------|---------|------|
| Invitation not found | 404 Not Found | No body | No invitation exists with service=HMRC-TERSNT-ORG and the provided URN |
| Invalid JSON | 400 Bad Request | Invalid JSON | Request body must be valid JSON |
| Missing 'utr' field | 400 Bad Request | Error parsing JSON | The 'utr' field is required in the request body |
| Database error | 500 Internal Server Error | Error message | Unexpected database error during update |

## Important Notes

- ✅ This is a backend-to-backend endpoint called by the Trusts Enrolment Orchestrator
- ✅ No authentication required - it's an internal service call
- ✅ The endpoint is specifically for trust service transitions from non-taxable to taxable
- ✅ Updates are atomic - all fields updated in a single MongoDB operation
- ✅ Both clientId and suppliedClientId fields are updated to the new UTR
- ⚠️ Only works for HMRC-TERSNT-ORG to HMRC-TERS-ORG transitions
- ⚠️ The URN must match an existing invitation with service=HMRC-TERSNT-ORG
- ⚠️ Does not validate the format of the URN or UTR - assumes orchestrator provides valid values
- ⚠️ Returns 404 for any invitation not found - does not distinguish between different reasons

## Related Documentation

- **ACR04**: Get All Invitations - Lists all invitations including those that have been updated
- **ACR05**: Create Invitation - Creates the initial invitation that may later be updated by this endpoint
- **ACR06**: Cancel Invitation - Alternative action that can be taken on invitations

