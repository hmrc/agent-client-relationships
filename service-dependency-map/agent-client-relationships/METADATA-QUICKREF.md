# METADATA QUICK REFERENCE

## Purpose

Small quick-reference for inserting analysis metadata into the three document types used in the service-dependency-map. Use these snippets when updating `.mmd`, `.md`, or `.json` files so every analysis is traceable to a Git commit SHA.

## How to get the current commit SHA

Run this in the repository root:

```bash
git rev-parse HEAD
```

## Mermaid (.mmd) snippet

Place these three comment lines at the top of each `.mmd` file:

```text
%% Last Updated: 2025-11-24
%% Git Commit SHA: b2138b4e3958677748c1820c3d715d4fbb9d3b2c
%% Analysis Version: 1.0
```

(These are Mermaid-style comments, not a Mermaid diagram block.)

## Markdown (.md) snippet

Add this block at the end of the markdown file, under a horizontal rule:

---

### Document metadata

**Last Updated:** 2025-11-24  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0

## JSON (.json) snippet

Add this `metadata` object at the root of the JSON file. Include it as a valid JSON object when editing `.json` files:

```json
{
  "metadata": {
    "lastUpdated": "2025-11-24",
    "gitCommitSha": "b2138b4e3958677748c1820c3d715d4fbb9d3b2c",
    "analysisVersion": "1.0"
  }
}
```

If your `.json` file already has a root object, merge the `metadata` object into it rather than nesting a second root object.

## Mermaid MCP validation config

Use this minimal config when calling the Mermaid Collaboration Platform (MCP) validator or adding a CI validation step that calls the MCP API:

```json
{
  "mermaid-mcp": {
    "url": "https://mcp.mermaidchart.com/mcp",
    "type": "http"
  }
}
```

## Notes and best practices

- Always update the `lastUpdated` date and the `gitCommitSha` when regenerating analysis files.
- Use the full 40-character commit SHA for traceability.
- Prefer the human-readable name `EACD (Enrolment Store Proxy)` in `.md` files when referring to the enrolment-store-proxy service.
- Mermaid validation can raise errors for reserved keywords used as aliases (for example `par`). If you see alias-related parser errors, rename the alias (e.g., `P1`, `PAR1`) and re-run validation.
- Keep participant aliases short and avoid reserved words such as `par`, `opt`, `alt`, and `loop`.

---
