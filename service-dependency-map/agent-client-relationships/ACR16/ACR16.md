# ACR16: Accept Authorization Request (Create Relationship)

## Overview

⚠️ **MOST COMPLEX ENDPOINT** - Allows client or Stride user to accept a pending invitation and **CREATE the actual agent-client relationship**. Unlike ACR15 (simple rejection), this endpoint:

- Creates relationships in **EACD** (via allocateEnrolmentToAgent) and/or **ETMP** (via HIP)
- Has **service-specific flows** for PIR, MTD-IT, MTD-IT-SUPP, Alt-ITSA, and other services
- **Deauthorizes existing agents** (logic varies by service)
- Manages **partial authorizations** (Alt-ITSA/IR-SA)
- Updates invitation status to **Accepted** or **PartialAuth**
- Sends acceptance email, updates friendly names, audits events
- Can return **423 Locked** if relationship creation is locked

## API Details

- **API ID**: ACR16
- **Method**: PUT
- **Path**: `/authorisation-response/accept/{invitationId}`
- **Authentication**: Two-phase (lookup first, then validate client OR Stride user)
- **Audience**: internal
- **Controller**: AuthorisationAcceptController
- **Controller Method**: `accept`
- **Complexity**: ⚠️ **VERY HIGH**

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| invitationId | String | Yes | Unique invitation identifier |

## Response

### Success Response (204 No Content)

Invitation accepted and relationship created successfully

### Error Responses

- **423 Locked**: Relationship creation is locked (CreateRelationshipLocked) - retry later
- **403 Forbidden**: Invitation not found, not pending, or user not authorized
- **401 Unauthorized**: User not authenticated
- **500 Internal Server Error**: Various failures during relationship creation

## Service Architecture

See ACR16.mmd for complete sequence diagram showing service-specific flows.

## Service-Specific Flows

### 1. PIR (Personal Income Record)

- **Uses**: agent-fi-relationship.createRelationship()
- **Deauth**: Handled by agent-fi-relationship internally
- **Status**: Accepted
- **Note**: Does NOT use CreateRelationshipsService

### 2. MTD-IT Alt-ITSA (Alternative ITSA / IR-SA Partial Auth)

- **Flow**: Deauth existing partial auth → Create new partial auth record
- **Storage**: MongoDB partial_auth collection
- **Status**: **PartialAuth** (not Accepted)
- **Note**: Does NOT create relationship in EACD/ETMP

### 3. MTD-IT-SUPP Alt-ITSA

- **Flow**: Create partial auth record (no deauth of current agent)
- **Status**: PartialAuth
- **Note**: Does NOT deauth current agent

### 4. MTD-IT (Normal/Full Authorization)

- **Flow**:
  1. Deauth existing partial auth for this NINO
  2. Delete same-agent relationship (if exists)
  3. CreateRelationshipsService → EACD allocateEnrolmentToAgent
  4. CreateRelationshipsService → ETMP via HIP
- **Status**: Accepted
- **Note**: Automatically deauths current ITSA agent, manually deauths alt-itsa

### 5. MTD-IT-SUPP (Normal)

- **Flow**:
  1. Delete same-agent relationship (if exists)
  2. CreateRelationshipsService → EACD + ETMP
- **Status**: Accepted

### 6. Other Services (VAT, Trusts, CGT, PPT, CBC, Pillar2)

- **Flow**: CreateRelationshipsService → EACD allocateEnrolmentToAgent + ETMP via HIP
- **Status**: Accepted

## Business Logic

### Delete Same-Agent Relationship (MTD-IT/MTD-IT-SUPP)

**Purpose**: Prevents agent having both main and supp for same client

**When**: Agent accepting new main when they have supp (or vice versa)

**Action**: Removes existing relationship before creating new one

### Deauth Accepted Invitations

**Purpose**: Client can only have one active agent per service

**Execution**: Async (non-blocking)

**Action**: Deauthorizes other previously accepted invitations for this client/service

### Partial Authorization (Alt-ITSA)

**What**: IR-SA partial authorization for clients not fully signed up for MTD-IT

**Status**: PartialAuth (not Accepted)

**Storage**: MongoDB partial_auth collection

**Deauth**: Existing partial auth deactivated before creating new one

### CreateRelationshipLocked

**What**: Relationship creation can be locked (concurrency control)

**Response**: 423 Locked

**Action**: Client should retry later

## Dependencies

### External Services

- **EACD (Enrolment Store Proxy)**: allocateEnrolmentToAgent creates delegation
- **ETMP via HIP**: Creates tax service relationships
- **agent-fi-relationship**: Creates PIR relationships
- **EmailService**: Sends acceptance confirmation

### Internal Services

- **AuthorisationAcceptService**: Orchestrates complex acceptance flow
- **CreateRelationshipsService**: Creates relationships in EACD and ETMP
- **ItsaDeauthAndCleanupService**: Handles MTD-IT/MTD-IT-SUPP deauth
- **PartialAuthRepository**: Manages IR-SA partial authorizations
- **FriendlyNameService**: Updates client friendly names

### Database Collections

- **invitations**: UPDATE status to Accepted or PartialAuth
- **partial_auth**: CREATE for Alt-ITSA, DELETE for deauth

## Important Notes

- ⚠️ **MOST COMPLEX ENDPOINT**: Multiple service-specific flows with different logic
- ⚠️ **CREATES ACTUAL RELATIONSHIPS**: In EACD and/or ETMP (unlike ACR15 which just updates status)
- ⚠️ **NOT ATOMIC**: Multiple steps, can partially fail
- ⚠️ **423 LOCKED**: Can return Locked status - client must retry
- ✅ **Service-Specific**: PIR, MTD-IT, MTD-IT-SUPP, Alt-ITSA, others all handled differently
- ✅ **Deauth Logic**: Varies by service - some automatic, some manual
- ✅ **Partial Auth**: Alt-ITSA uses PartialAuth status, not Accepted
- ✅ **Async Operations**: Email and friendly name updates are async
- ✅ **Audit Details**: Includes how relationship was created (client vs Stride)

## Comparison with ACR15 (Reject)

| Aspect | ACR15 (Reject) | ACR16 (Accept) |
|--------|---------------|----------------|
| **Complexity** | Low | **VERY HIGH** |
| **Creates Relationship** | No | **Yes** (EACD/ETMP) |
| **Service-Specific Logic** | No | **Yes** (6+ different flows) |
| **Deauth Existing Agents** | No | **Yes** (service-specific) |
| **Can Return Locked** | No | **Yes** (423) |
| **Status** | Rejected | Accepted or PartialAuth |
| **External Services** | None | EACD, ETMP, agent-fi-relationship |

## Related Documentation

- **ACR15**: Reject Authorization Request (simpler complement)
- **ACR12**: Validate Invitation for Client (before accept/reject)
- **CreateRelationshipsService**: Core relationship creation logic
- **EACD**: Enrolment Store Proxy for delegation

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
