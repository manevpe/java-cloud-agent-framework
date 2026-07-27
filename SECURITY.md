# Security Policy

## Supported Versions

This project is pre-1.0 and does not yet maintain multiple supported
release branches. Security fixes are made against
`main` only.

| Version | Supported |
| ------- | --------- |
| main    | ✅        |

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security
vulnerabilities. Instead, report it privately using
[GitHub Security Advisories](/security/advisories/new)
for this repository.

Include, where possible:

- A description of the vulnerability and its potential impact
- Steps to reproduce (a minimal workflow YAML, agent/tool module, or
  request/payload that triggers it)
- The affected module(s) (`core`, a specific `agents/*` or `tools/*`
  module, etc.)

You should expect an initial response within 5 business days. We'll
work with you to confirm the issue, agree on a disclosure timeline, and
credit you in the fix (unless you prefer to remain anonymous).

## Scope

This policy covers the code in this repository (`core`, built-in
`agents/*`/`tools/*` modules, and the Helm chart under `deploy/`).
Vulnerabilities in third-party dependencies should be reported upstream,
but feel free to flag them here too if they affect how this project uses
them.
