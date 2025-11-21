# Agent Client Relationships - Service Dependency Documentation

This directory contains comprehensive documentation of all API endpoints, service dependencies, and data structures for the `agent-client-relationships` microservice.

## Contents

### API Endpoint Documentation (ACR01-ACR34)

Each API endpoint is documented in its own folder with three files:
- **`.mmd`** - Mermaid sequence diagram (visual flow)
- **`.md`** - Markdown documentation (detailed description)
- **`.json`** - JSON metadata (structured data)

#### Internal APIs (ACR01-ACR30)
Used by other HMRC microservices and internal platforms.

#### External APIs (ACR31-ACR34)
Used by external agent software vendors via the API platform.

### MongoDB Collections

- **`mongo.md`** - Complete documentation of all MongoDB collections used by the service
- **`mongo-collections.json`** - Machine-readable list of collections

### Documentation Standards

- **`ANALYSIS-GUIDE.md`** - Complete guide for creating and maintaining API documentation
- **`METADATA-QUICKREF.md`** - Quick reference for adding Git commit SHA tracking
- **`get-commit-sha.sh`** - Helper script to generate metadata snippets

### Service Metadata

- **`agent-client-relationships.service-meta.yaml`** - Service metadata for MDTP platform

## Git Commit SHA Tracking

**All documentation files include the Git commit SHA** of the codebase they were analyzed against. This ensures:
- ✅ Traceability of documentation to specific code versions
- ✅ Easy identification of outdated documentation
- ✅ Audit trail of when analysis was performed

### How to Use

When creating or updating documentation:

1. Run the helper script:
   ```bash
   ./get-commit-sha.sh
   ```

2. Copy the appropriate snippet for your file type (.mmd, .md, or .json)

3. See `METADATA-QUICKREF.md` for quick reference

4. See `ANALYSIS-GUIDE.md` for complete standards

## Structure

```
agent-client-relationships/
├── README.md                          (this file)
├── ANALYSIS-GUIDE.md                  (documentation standards)
├── METADATA-QUICKREF.md               (quick reference)
├── get-commit-sha.sh                  (helper script)
├── mongo.md                           (MongoDB collections)
├── mongo-collections.json             (collections list)
├── agent-client-relationships.service-meta.yaml
├── ACR01/
│   ├── ACR01.mmd                      (sequence diagram)
│   ├── ACR01.md                       (documentation)
│   └── ACR01.json                     (metadata)
├── ACR02/
│   ├── ACR02.mmd
│   ├── ACR02.md
│   └── ACR02.json
...
└── ACR34/
    ├── ACR34.mmd
    ├── ACR34.md
    └── ACR34.json
```

## Quick Links

### Getting Started
1. **New to the service?** Start with `mongo.md` to understand the data model
2. **Need API documentation?** Browse the ACRxx folders
3. **Creating documentation?** Read `ANALYSIS-GUIDE.md`
4. **Quick update?** Use `get-commit-sha.sh` and `METADATA-QUICKREF.md`

### Common Tasks
- **Find an endpoint**: Browse ACR01-ACR34 folders by ID
- **Understand data flow**: Look at the .mmd sequence diagrams
- **Get detailed info**: Read the .md markdown files
- **Machine-readable data**: Use the .json metadata files

### Standards & Guidelines
- **Documentation structure**: `ANALYSIS-GUIDE.md`
- **Metadata format**: `METADATA-QUICKREF.md`
- **Example implementation**: `ACR01/` folder

## Terminology

- **EACD**: Enrolment Store Proxy (enrolment-store-proxy) - manages user enrolments and delegated access
- **ETMP**: Electronic Tax Management Platform - legacy HMRC tax system
- **DES**: Data Exchange Service - legacy integration layer
- **IF/HIP**: Integration Framework / HMRC Integration Platform - modern integration layer
- **ARN**: Agent Reference Number - unique identifier for agents
- **NINO**: National Insurance Number
- **UTR**: Unique Taxpayer Reference
- **VRN**: VAT Registration Number

## Maintenance

Documentation should be updated when:
- API endpoints are added, modified, or removed
- Service dependencies change
- MongoDB collections are modified
- Integration patterns change

Always include the current Git commit SHA when updating documentation.

## Version History

- **1.0** (2025-11-20): Initial comprehensive documentation with Git commit SHA tracking

## Related Documentation

- [Main README](../../README.md) - Service README
- [Recovery Documentation](../../docs/recovery.md) - Recovery process details
- [OpenAPI Spec](../../openapi.yaml) - API specification

---

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`

