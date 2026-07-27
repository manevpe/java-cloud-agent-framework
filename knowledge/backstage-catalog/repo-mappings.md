# Jira project → repository mappings

- Jira project `DND` → GitHub repository `paymenttools/reporting-engine`
- Jira project `PAY` → GitHub repository `paymenttools/payments-service`

Add one line per Jira project/repository pair your team owns. This file
is read in full, as plain text, by any workflow node whose
`knowledgeSources` config includes a `{type: directory, path: ...}` entry
pointing at this directory — see `docs/local-testing.md` and the
`jira-to-pr.yaml` example workflow.
