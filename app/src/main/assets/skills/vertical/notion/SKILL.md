---
name: notion
description: Search, read, and create Notion pages and databases
version: "1.0"
author: MobileClaw Built-in
tools_required: http
---
# Notion Integration
## Role
You help the user manage their Notion workspace — search pages, read content, create new pages, and query databases. All API calls go through the http tool.
Keywords: "notion", "note", "筆記", "create page", "wiki", "database"
## Setup
The user must configure their Notion API token in MobileClaw Settings. This is an internal integration token from https://www.notion.so/my-integrations.
- Token location: Settings > API Keys > Notion
- The integration must be shared with any pages/databases the user wants to access (via Notion's "Connect to" menu on a page)
## API Configuration
- Base URL: `https://api.notion.com/v1`
- Required headers on every request:
  - `Authorization: Bearer {NOTION_TOKEN}`
  - `Notion-Version: 2022-06-28`
  - `Content-Type: application/json`
## Standard Workflows
### Search Pages and Databases
Find pages or databases by keyword.
1. Use http tool:
   ```
   http POST https://api.notion.com/v1/search
   Headers: Authorization: Bearer {token}, Notion-Version: 2022-06-28, Content-Type: application/json
   Body: {"query": "meeting notes", "sort": {"direction": "descending", "timestamp": "last_edited_time"}}
   ```
2. Parse results array — each item has `id`, `object` (page or database), `properties`, and `url`
3. Present results to user with title and last edited time
4. To narrow results to pages only, add `"filter": {"value": "page", "property": "object"}` to body
5. To narrow to databases only, use `"filter": {"value": "database", "property": "object"}`
### Read Page Content
Fetch the block children (actual content) of a page.
1. First get page metadata:
   ```
   http GET https://api.notion.com/v1/pages/{page_id}
   Headers: Authorization: Bearer {token}, Notion-Version: 2022-06-28
   ```
2. Then fetch page blocks (the actual content):
   ```
   http GET https://api.notion.com/v1/blocks/{page_id}/children?page_size=100
   Headers: Authorization: Bearer {token}, Notion-Version: 2022-06-28
   ```
3. If `has_more` is true in response, fetch next page with `start_cursor` parameter:
   ```
   http GET https://api.notion.com/v1/blocks/{page_id}/children?page_size=100&start_cursor={next_cursor}
   ```
4. Parse block types: paragraph, heading_1/2/3, bulleted_list_item, numbered_list_item, to_do, code, image, etc.
5. Extract text from `rich_text` arrays within each block
6. Present content in a readable format to the user
### Create a New Page
Create a page inside an existing page or database.
1. To create under a parent page:
   ```
   http POST https://api.notion.com/v1/pages
   Headers: Authorization: Bearer {token}, Notion-Version: 2022-06-28, Content-Type: application/json
   Body: {
     "parent": {"page_id": "PARENT_PAGE_ID"},
     "properties": {
       "title": [{"text": {"content": "My New Page"}}]
     },
     "children": [
       {
         "object": "block",
         "type": "heading_2",
         "heading_2": {
           "rich_text": [{"type": "text", "text": {"content": "Section Title"}}]
         }
       },
       {
         "object": "block",
         "type": "paragraph",
         "paragraph": {
           "rich_text": [{"type": "text", "text": {"content": "Body text goes here."}}]
         }
       }
     ]
   }
   ```
2. To create inside a database (with properties):
   ```
   http POST https://api.notion.com/v1/pages
   Headers: Authorization: Bearer {token}, Notion-Version: 2022-06-28, Content-Type: application/json
   Body: {
     "parent": {"database_id": "DATABASE_ID"},
     "properties": {
       "Name": {"title": [{"text": {"content": "Task Title"}}]},
       "Status": {"select": {"name": "In Progress"}},
       "Priority": {"select": {"name": "High"}},
       "Due Date": {"date": {"start": "2026-04-01"}}
     },
     "children": [
       {
         "object": "block",
         "type": "paragraph",
         "paragraph": {
           "rich_text": [{"type": "text", "text": {"content": "Task details here."}}]
         }
       }
     ]
   }
   ```
3. Confirm creation by returning the new page URL from the response
### Query a Database
Filter and sort database entries.
1. Use http tool:
   ```
   http POST https://api.notion.com/v1/databases/{database_id}/query
   Headers: Authorization: Bearer {token}, Notion-Version: 2022-06-28, Content-Type: application/json
   Body: {
     "filter": {
       "and": [
         {"property": "Status", "select": {"equals": "In Progress"}},
         {"property": "Priority", "select": {"equals": "High"}}
       ]
     },
     "sorts": [
       {"property": "Due Date", "direction": "ascending"}
     ],
     "page_size": 50
   }
   ```
2. Handle pagination: if `has_more` is true, re-query with `"start_cursor": "{next_cursor}"`
3. Parse property values by type: title, rich_text, select, multi_select, date, number, checkbox, url, etc.
4. Present results as a formatted list or table
### Append Content to Existing Page
Add new blocks to the end of a page.
1. Use http tool:
   ```
   http PATCH https://api.notion.com/v1/blocks/{page_id}/children
   Headers: Authorization: Bearer {token}, Notion-Version: 2022-06-28, Content-Type: application/json
   Body: {
     "children": [
       {
         "object": "block",
         "type": "paragraph",
         "paragraph": {
           "rich_text": [{"type": "text", "text": {"content": "Appended content."}}]
         }
       }
     ]
   }
   ```
## Guidelines
- Always confirm before creating or modifying pages
- When searching, try multiple query terms if the first search returns no results
- Database property names are case-sensitive — match them exactly
- Page IDs can be extracted from Notion URLs: `notion.so/Page-Title-{32_char_hex_id}`
- Strip hyphens from URL-style IDs before using in API calls
- Rich text content has a 2000 character limit per block — split long content into multiple blocks
- If the token is missing or invalid, guide the user to Settings to configure it
