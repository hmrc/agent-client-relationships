# ACR14: Get Agent Details

## Overview

Retrieves public details about an agent including agency name, email, and suspension status. This is a **simple passthrough endpoint** that queries agent-assurance for agent information with no additional business logic. Returns details even if the agent is suspended.

## API Details

- **API ID**: ACR14
- **Method**: GET
- **Path**: `/agent/{arn}/details`
- **Authentication**: Agent authentication via `withAuthorisedAsAgent`
- **Audience**: internal
- **Controller**: AgentDetailsController
- **Controller Method**: `getAgentDetails`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| arn | Arn | Yes | Agent Reference Number (e.g., TARN0000001) |

## Query Parameters

None

## Response

### Success Response (200 OK)

Returns AgentDetailsDesResponse with agency details:

```json
{
  "agencyDetails": {
    "agencyName": "ABC Accountants Ltd",
    "agencyEmail": "info@abcaccountants.co.uk"
  },
  "suspensionDetails": null
}
```

**If agent is suspended**:
```json
{
  "agencyDetails": {
    "agencyName": "Suspended Agent Ltd",
    "agencyEmail": "contact@suspendedagent.co.uk"
  },
  "suspensionDetails": {
    "suspensionStatus": true,
    "regimes": ["ITSA", "VATC"]
  }
}
```

**Fields**:
- `agencyDetails.agencyName`: Agent's trading name
- `agencyDetails.agencyEmail`: Agent's contact email
- `suspensionDetails`: Optional - present only if agent is suspended
  - `suspensionStatus`: Boolean (true if suspended)
  - `regimes`: Array of suspended tax regimes

### Error Responses

- **401 Unauthorized**: Agent not authenticated

## Service Architecture

See ACR14.mmd for complete sequence diagram.

### Flow

1. **Authenticate Agent**: Via `withAuthorisedAsAgent`
2. **Query agent-assurance**: Call `getAgentRecord(arn)`
3. **Return Response**: Pass through agent-assurance response directly

## Dependencies

### External Services

- **agent-assurance**: Provides agent record
  - Method: `getAgentRecord(arn)`
  - Endpoint: `/agent-assurance/agent-record/:arn`
  - Returns details **even if agent suspended**

## Business Logic

### Passthrough Endpoint

This endpoint has **no business logic** - it's a simple wrapper around agent-assurance:

```scala
agentAssuranceService.getAgentRecord(arn).map(agent => Ok(Json.toJson(agent)))
```

### No ARN Validation

⚠️ **Important**: The endpoint does NOT validate that the ARN in the path matches the authenticated agent's ARN.

**Implication**: Any authenticated agent can query details of any other agent.

**Justification**: Agency name and email are considered public information.

### Suspension Status

Uses `getAgentRecord` (not `getNonSuspendedAgentRecord`):
- Returns details **even if agent is suspended**
- `suspensionDetails` field indicates suspension status

## Use Cases

### 1. Agent Views Own Details

**Request**: `GET /agent/TARN0000001/details`

**Authenticated As**: TARN0000001

**Response**:
```json
{
  "agencyDetails": {
    "agencyName": "ABC Accountants Ltd",
    "agencyEmail": "info@abcaccountants.co.uk"
  },
  "suspensionDetails": null
}
```

**Frontend Action**: Display agent's name and email in profile section

### 2. Agent Checks Suspension Status

**Response**:
```json
{
  "agencyDetails": {
    "agencyName": "My Agency",
    "agencyEmail": "contact@myagency.co.uk"
  },
  "suspensionDetails": {
    "suspensionStatus": true,
    "regimes": ["ITSA"]
  }
}
```

**Frontend Action**: Show warning - "Your agency is currently suspended for Making Tax Digital for Income Tax"

### 3. Agent Queries Another Agent's Details

**Request**: `GET /agent/TARN0000002/details`

**Authenticated As**: TARN0000001 (different agent)

**Response**:
```json
{
  "agencyDetails": {
    "agencyName": "Other Agent Ltd",
    "agencyEmail": "info@otheragent.co.uk"
  },
  "suspensionDetails": null
}
```

**Note**: Agent A can query details of Agent B - no validation that ARN matches authenticated agent

### 4. Internal Service Needs Agent Name

**Scenario**: Client viewing invitation needs to see agent name

**Use**: Internal service calls this endpoint to get agent name for display purposes

## Error Handling

| Error | Response | Note |
|-------|----------|------|
| Agent not authenticated | 401 Unauthorized | Standard auth failure |
| ARN not found | Error from agent-assurance | No explicit handling in controller |

## Security Considerations

### Any Agent Can Query Any Agent

**Behavior**: No validation that ARN in path matches authenticated agent

**Example**: Agent TARN0000001 can query `/agent/TARN0000002/details`

**Severity**: Low

**Justification**: Agency name and email are considered public information available in agent-assurance

## Comparison with Similar Endpoints

### getAgentRecord vs getNonSuspendedAgentRecord

| Method | Behavior | Used By |
|--------|----------|---------|
| `getAgentRecord` | Returns details even if suspended | **ACR14**, ACR13 |
| `getNonSuspendedAgentRecord` | Returns None if suspended | ACR10, ACR12 |

## Important Notes

- ✅ **Simple Passthrough**: No business logic - direct wrapper around agent-assurance
- ✅ **Agent Auth Required**: Must be authenticated agent (any agent)
- ✅ **Works When Suspended**: Returns details even if agent suspended
- ✅ **Includes Suspension Info**: `suspensionDetails` field shows suspension status
- ⚠️ **No ARN Validation**: Any agent can query any other agent's details
- ⚠️ **No Error Handling**: Relies on agent-assurance error handling
- ⚠️ **No Caching**: Queries agent-assurance on every request
- ✅ **Public Info**: Only returns agency name and email (public data)

## Related Documentation

- **agent-assurance**: External service that stores agent records
- **ACR13**: Get Authorization Request Info (also uses getAgentRecord)
- **ACR10**: Validate Link (uses getNonSuspendedAgentRecord instead)

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
