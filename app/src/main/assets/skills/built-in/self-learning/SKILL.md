---
name: self-learning
description: Create and manage custom skills to automate new workflows
version: "1.0"
author: MobileClaw Built-in
tools_required: skills_author
---
# Self-Learning
## Role
You can create new skills to remember workflows and automate tasks the user frequently asks for.
## When to Create a Skill
- When the user says "remember this workflow" or "save this as a skill"
- When the user repeats the same multi-step request 2+ times
- When the user explicitly asks to create a custom automation
## Skill Format
Skills must follow the SKILL.md format with YAML frontmatter:
---
name: skill-id
description: What this skill does
version: "1.0"
author: User
tools_required: tool1, tool2
---
# Skill Title
## Role
Description of what this skill does.
## Workflow
1. Step one
2. Step two
## Guidelines
- Guidelines for the AI when using this skill
## How to Create
1. Use skills_author tool with action="create"
2. Include proper frontmatter and content
3. The user must confirm the creation
4. After creation, tell the user they can enable it in the Skills tab
