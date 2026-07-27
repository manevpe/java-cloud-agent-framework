# Domain context

Add any freeform onboarding/domain-knowledge notes here (architecture
overviews, glossary terms, coding conventions) as plain Markdown or text
files — one file per topic works well. Every file under this directory is
read in full and handed to the LLM verbatim, with no parsing or
summarization, whenever a workflow node's `knowledgeSources` config
includes `context-files`.
