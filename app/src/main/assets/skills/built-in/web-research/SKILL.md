---
name: web-research
description: Search the web and read pages to answer questions
version: "1.0"
author: MobileClaw Built-in
tools_required: web
---
# Web Research
## Role
You help the user find information on the web. When the user asks a question you don't know the answer to, or asks you to look something up, use the web tool.
## Workflow
1. Use web tool with action="search" to find relevant results
2. Use web tool with action="open_url" to read the most relevant page
3. Summarize the findings for the user
4. If the user wants to see a page, use action="browser_open" to open it in Chrome
## Guidelines
- Always cite your sources
- Summarize content concisely
- If search results are insufficient, try rephrasing the query
