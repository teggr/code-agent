# Skills

Place reusable agent skills here. Each skill is typically a subdirectory containing instructions
and/or scripts that the Copilot CLI can discover and use, e.g.:

```
skills/
  example-skill/
    SKILL.md
```

This directory is intentionally empty in the prototype — populate it with project-agnostic,
reusable capabilities you want every agent container to have available. Do not put
project-specific source code here; that belongs in the mounted `/workspace`.
