## Project Overview

Jetpack is a Kotlin-based Paper plugin. It parses and runs the Jetpack scripting language, and connects scripts with Bukkit/Paper events, commands, intervals, and extension modules.

Before starting work, inspect the relevant implementation first and understand how the current flow works before making changes. If requirements or behavior are unclear, ask a specific question instead of guessing.

## Working Principles

- Keep changes small and clearly scoped.
- Prefer the existing structure and code style.
- Be especially careful with changes that affect broad areas such as parsing, type checking, runtime behavior, or Paper integration.
- Consider compatibility impact when changing public behavior, script syntax, commands, permissions, or configuration.
- Avoid unnecessary large refactors or new abstractions.
- Do not revert existing user changes.

## Code Guidelines

- Use clear names and keep control flow simple.
- Catch exceptions only when there is meaningful handling. Do not hide failures; preserve enough context to understand the cause.
- Code with many comments is often not a sign of good code. Well-written code should be understandable without relying on comments.
- If explanatory comments are frequently needed, that may indicate the code structure should be improved. In that case, refine the structure first; when comments are truly necessary, explain why something is done rather than what the code is doing.
- When working with Bukkit/Paper APIs, consider server threading and server stability.
- When expanding script-accessible functionality, consider permissions, input validation, and unintended server access.

## Jetpack-Specific Notes

- When changing script syntax, verify that parsing, type checking, and runtime behavior stay consistent.
- Keep error messages useful for both script authors and server operators.
- When changing command behavior, also consider permissions, user-facing messages, and tab completion flow.
- Manage user-facing strings consistently with the localization system.
- Do not edit generated outputs or build artifacts as if they were source files.

## Collaboration

- Summarize the change scope and verification status when work is complete.
- Clearly state anything that was not verified.
- Create commits only when explicitly requested by the user.
- Follow the repository's contributing document for contribution flow, branches, commits, and PRs.
