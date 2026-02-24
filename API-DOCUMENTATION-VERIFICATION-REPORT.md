# API Documentation Verification Report

## Agent Client Relationships Service

**Date:** 22 January 2026  
**Service:** agent-client-relationships  
**Documentation Location:** `service-dependency-map/agent-client-relationships/agent-client-relationships.service-meta.yaml`

---

## Executive Summary

Analyzed 34 documented APIs against the actual codebase. **Found 5 APIs (15%) that are documented but no longer exist in the codebase**. These endpoints were removed in October 2025 but remain in the documentation, potentially causing confusion for developers and integration teams.

### Issues Identified

| Issue | Count | Severity |
|-------|-------|----------|
| **APIs documented but removed from code** | 5 | 🔴 **HIGH** |
| **APIs with accurate documentation** | 29 | ✅ OK |

---

## Detailed Findings

### ❌ DEPRECATED APIs (Documented but Removed)

These 5 APIs are documented in `agent-client-relationships.service-meta.yaml` but **do not exist** in the current codebase:

#### **ACR02: Get Inactive Agent Relationships**

- **Documented Path:** `GET /agent/relationships/inactive`
- **Status:** **REMOVED** (October 14, 2025, v1.182.0)
- **Issue:** Documentation shows this endpoint exists with full description, dependencies (HIP/ETMP), and response formats
- **Impact:** Developers may attempt to call this non-existent endpoint
- **Recommendation:** **REMOVE** from `agent-client-relationships.service-meta.yaml`

#### **ACR05: Get Client Active Relationships**

- **Documented Path:** `GET /client/relationships/active`
- **Status:** **REMOVED** (October 14, 2025, v1.182.0)
- **Issue:** Documentation describes endpoint as "Retrieves all active agent relationships for the authenticated client across all their enrolled services"
- **Impact:** Frontend teams may expect this endpoint to exist
- **Recommendation:** **REMOVE** from `agent-client-relationships.service-meta.yaml`

#### **ACR06: Get Client Inactive Relationships**

- **Documented Path:** `GET /client/relationships/inactive`
- **Status:** **REMOVED** (October 14, 2025, v1.182.0)
- **Issue:** Full documentation exists including response models and error handling
- **Impact:** Client journey documentation may reference this endpoint
- **Recommendation:** **REMOVE** from `agent-client-relationships.service-meta.yaml`

#### **ACR07: Get Client Relationship by Service**

- **Documented Path:** `GET /client/relationships/service/{service}`
- **Status:** **REMOVED** (October 14, 2025, v1.182.0)
- **Issue:** Documentation shows endpoint accepts service parameter and returns single relationship
- **Impact:** Service-specific relationship queries will fail
- **Recommendation:** **REMOVE** from `agent-client-relationships.service-meta.yaml`

#### **ACR26: Get Stride Client Relationships**

- **Documented Path:** `GET /relationships/service/{service}/client/{clientIdType}/{clientId}`
- **Status:** **REMOVED** (October 14, 2025, v1.182.0)
- **Issue:** Documented as Stride-only endpoint for HMRC staff
- **Impact:** Stride users expecting this endpoint will encounter 404 errors
- **Recommendation:** **REMOVE** from `agent-client-relationships.service-meta.yaml`

---

### ✅ VERIFIED APIs (Active and Documented)

The remaining **29 APIs** are correctly documented and exist in the codebase:

| API ID | Method | Path | Status |
|--------|--------|------|--------|
| ACR01 | GET | /agent/{arn}/service/{service}/client/{clientIdType}/{clientId} | ✅ Active |
| ACR03 | DELETE | /agent/{arn}/terminate | ✅ Active |
| ACR04 | GET | /agent/{arn}/client/{nino}/legacy-mapped-relationship | ✅ Active |
| ACR08 | GET | /client/authorisations-relationships | ✅ Active |
| ACR09 | GET | /client/{service}/details/{clientId} | ✅ Active |
| ACR10 | GET | /agent/agent-reference/uid/{uid}/{normalizedAgentName} | ✅ Active |
| ACR11 | GET | /agent/agent-link | ✅ Active |
| ACR12 | POST | /client/validate-invitation | ✅ Active |
| ACR13 | GET | /client/authorisation-request-info/{invitationId} | ✅ Active |
| ACR14 | GET | /agent/{arn}/details | ✅ Active |
| ACR15 | PUT | /client/authorisation-response/reject/{invitationId} | ✅ Active |
| ACR16 | PUT | /authorisation-response/accept/{invitationId} | ✅ Active |
| ACR17 | POST | /agent/{arn}/authorisation-request | ✅ Active |
| ACR18 | GET | /agent/{arn}/authorisation-request-info/{invitationId} | ✅ Active |
| ACR19 | GET | /agent/{arn}/authorisation-requests | ✅ Active |
| ACR20 | POST | /agent/{arn}/remove-authorisation | ✅ Active |
| ACR21 | PUT | /agent/cancel-invitation/{invitationId} | ✅ Active |
| ACR22 | POST | /itsa-post-signup/create-relationship/{nino} | ✅ Active |
| ACR23 | POST | /invitations/trusts-enrolment-orchestrator/{urn}/update | ✅ Active |
| ACR24 | GET | /customer-status | ✅ Active |
| ACR25 | PUT | /cleanup-invitation-status | ✅ Active |
| ACR27 | POST | /stride/active-relationships | ✅ Active |
| ACR28 | GET | /stride/client-details/service/{service}/client/{clientIdType}/{clientId} | ✅ Active |
| ACR29 | GET | /stride/irv-relationships/{nino} | ✅ Active |
| ACR30 | GET | /stride/partial-auths/nino/{nino} | ✅ Active |
| ACR31 | POST | /api/{arn}/invitation | ✅ Active |
| ACR32 | GET | /api/{arn}/invitation/{invitationId} | ✅ Active |
| ACR33 | GET | /api/{arn}/invitations | ✅ Active |
| ACR34 | POST | /api/{arn}/relationship | ✅ Active |

---

## Verification Methodology

### Sources Analyzed

1. **Documentation:**
   - `service-dependency-map/agent-client-relationships/agent-client-relationships.service-meta.yaml`
   - Individual API documentation in `service-dependency-map/agent-client-relationships/ACR*/`
   - `agent-client-relationships-analysis.md`

2. **Code:**
   - `conf/app.routes` - Route definitions
   - `app/uk/gov/hmrc/agentclientrelationships/controllers/*.scala` - Controller implementations
   - Git history to identify removed endpoints

### Verification Steps

For each documented API:

1. ✅ Verify route exists in `conf/app.routes`
2. ✅ Verify controller method exists and matches route
3. ✅ Verify path parameters match documentation
4. ✅ Check git history for deprecations/removals

---

## Why Were These APIs Removed?

### Removal Context (October 2025)

**Commit:** [9fe126e](https://github.com/hmrc/agent-client-relationships/commit/9fe126e906151937a192ad09fbcce07dab3a7334)  
**Tag:** v1.182.0  
**PR:** #454 [APB-10089]  
**Date:** October 14, 2025

**Removal Scope:**

- 7 route definitions removed from `conf/app.routes`
- RelationshipsController methods removed
- Supporting connectors, models, and tests deleted (1,731 total deletions)

**Likely Reasons:**

1. **Redundancy:** ACR08 (`/client/authorisations-relationships`) provides comprehensive data including both active/inactive relationships, making ACR05/ACR06/ACR07 redundant
2. **Consolidation:** Single endpoint (ACR08) replaces multiple specialized endpoints
3. **Maintenance:** Reducing API surface area simplifies maintenance
4. **Stride Migration:** ACR26 likely replaced by ACR27/ACR28 (Stride bulk endpoints)

---

## Impact Analysis

### Developer Impact

- **Medium:** Developers referencing documentation may attempt to call non-existent endpoints
- **Mitigation:** Update documentation immediately to prevent confusion

### Integration Impact

- **Low:** If any services still call these endpoints, they would already be failing since October 2025
- **Mitigation:** Verify no active callers exist (check service logs)

### Documentation Impact

- **High:** 15% of documented APIs are incorrect
- **Mitigation:** Remove deprecated APIs from all documentation sources

---

## Recommendations

### Immediate Actions (High Priority)

1. **Update `agent-client-relationships.service-meta.yaml`**
   - ❌ Remove ACR02, ACR05, ACR06, ACR07, ACR26
   - ✅ Retain 29 active APIs
   - Add deprecation notes section if needed

2. **Update Individual API Documentation**
   - Delete directories:
     - `service-dependency-map/agent-client-relationships/ACR02/`
     - `service-dependency-map/agent-client-relationships/ACR05/`
     - `service-dependency-map/agent-client-relationships/ACR06/`
     - `service-dependency-map/agent-client-relationships/ACR07/`
     - `service-dependency-map/agent-client-relationships/ACR26/`

3. **Update OpenAPI Specification**
   - Remove deprecated endpoints from `openapi.yaml`
   - Verify all documented endpoints are implemented

4. **Update Analysis Document**
   - Review `agent-client-relationships-analysis.md`
   - Remove references to deleted endpoints
   - Update journey flows to use ACR08 instead

### Documentation Best Practices

1. **Automated Verification**
   - Create script to verify documentation matches routes
   - Run as part of CI/CD pipeline
   - Flag discrepancies before merge

2. **Deprecation Process**
   - When removing APIs, immediately update documentation
   - Add deprecation warnings before removal
   - Document migration paths

3. **Version Tracking**
   - Add API version numbers to documentation
   - Track when APIs were added/removed
   - Link to git commits for major changes

---

## Migration Guidance

For teams currently using or expecting these endpoints:

### ACR02 → Replacement

**Old:** `GET /agent/relationships/inactive`  
**New:** Use ACR08 (`/client/authorisations-relationships`) and filter `inactiveRelationships` array  
**Alternative:** Query ETMP directly via HIP if agent-side view needed

### ACR05 → Replacement

**Old:** `GET /client/relationships/active`  
**New:** Use ACR08 (`/client/authorisations-relationships`) and access `activeRelationships` map

### ACR06 → Replacement

**Old:** `GET /client/relationships/inactive`  
**New:** Use ACR08 (`/client/authorisations-relationships`) and filter `inactiveRelationships` array

### ACR07 → Replacement

**Old:** `GET /client/relationships/service/{service}`  
**New:** Use ACR08 (`/client/authorisations-relationships`) and access `activeRelationships[service]`

### ACR26 → Replacement

**Old:** `GET /relationships/service/{service}/client/{clientIdType}/{clientId}`  
**New:** Use ACR28 (`/stride/client-details/service/{service}/client/{clientIdType}/{clientId}`) for Stride users

---

## Conclusion

The agent-client-relationships service documentation is **85% accurate** with **5 documented APIs that no longer exist**. These APIs were removed in October 2025 as part of a consolidation effort but documentation was not updated accordingly.

**Next Steps:**

1. Remove ACR02, ACR05, ACR06, ACR07, ACR26 from `service-meta.yaml`
2. Delete corresponding documentation directories
3. Update all references in analysis documents
4. Implement automated documentation verification
5. Establish deprecation workflow for future changes

---

**Generated by:** GitHub Copilot API Documentation Verification  
**Reviewed against:** agent-client-relationships codebase (January 2026)  
**Documentation Source:** service-dependency-map/agent-client-relationships/
