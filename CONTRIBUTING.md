# Contributing to Jetpack

Thank you for your interest in contributing to Jetpack. This document describes the basic collaboration guidelines for contributing to the project.

Jetpack is a Kotlin-based Paper plugin that includes its own scripting language parser, runtime, and Bukkit/Paper integration. Even small changes can affect script execution, server stability, or plugin API compatibility, so please clearly share the intent of your change and how you verified it.

## Table of Contents

- [Contributing to Jetpack](#contributing-to-jetpack)
  - [Table of Contents](#table-of-contents)
  - [General Principles](#general-principles)
  - [Branch Strategy](#branch-strategy)
  - [Branch Naming Guidelines](#branch-naming-guidelines)
  - [Commit Messages](#commit-messages)
  - [Pull Request](#pull-request)
  - [Code Change Guidelines](#code-change-guidelines)
  - [AI Agent Usage](#ai-agent-usage)

## General Principles

- Keep changes as small and clear as possible.
- Keep each PR focused on a single purpose.
- If behavior changes, explain the reason and impact in the PR.
- Do not hide failures. When handling errors, provide context that helps users or server administrators understand what happened.
- Do not commit sensitive information, personal tokens, server configuration files, or local environment files.

## Branch Strategy

The default target branch for PRs is `develop`.

- `main`: Stable branch. Only releases or stabilized changes should be merged here.
- `develop`: Integration branch. General features, bug fixes, and refactoring PRs should target this branch.
- Working branches: We recommend creating them from `develop`.

For urgent production issues or critical bug fixes, you may use a `hotfix/` branch. In that case, please explain in the PR description why the regular `develop` flow is not being used.

## Branch Naming Guidelines

Branch names should make the type and purpose of the change clear.

| Prefix | Purpose |
| :-: | :-- |
| `features/` | New features |
| `bugs/` | Bug fixes |
| `refactor/` | Structural improvements that do not intentionally change behavior |
| `hotfix/` | Urgent and critical fixes |
| `chore/` | Build, documentation, configuration, and maintenance tasks |
| `agents/` | Mostly AI-agent-driven contributions. Please use the `agents/Prefix/...` format. |

Branch naming rules:

- Use lowercase letters and hyphens.
- Clearly describe the target area or problem being addressed.
- If most of the work came from an AI Agent, please use `agents/Prefix/...`, where `Prefix` matches the change category. For example: `features`, `bugs`, `refactor`, `hotfix`, or `chore`.

## Commit Messages

Commit messages should briefly and clearly describe the nature of the change. This repository currently uses the following style.

Recommended prefixes:

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation change
- `refactor`: Structural change without behavior changes
- `chore`: Build, configuration, or maintenance task

Keep commits in reviewable units, but avoid leaving excessive intermediate work-in-progress commits.

## Pull Request

PRs should target the `develop` branch by default.

The PR title should describe the main change. The PR description should include:

- Purpose of the change
- Main changes made
- Impact on users or server operators
- Commands or tests used for verification
- Related issue numbers, if any, using `Closes #123` or `Fixes #123`
- Tool and model used, if you used an AI Agent

If there are behavior changes, compatibility impacts, or migration requirements, please call them out separately.

## Code Change Guidelines

Jetpack combines a scripting language engine with a Minecraft server plugin. Please follow these guidelines when making changes.

- Keep parser, type checker, and runtime changes as small and focused as possible.
- Consider server main-thread requirements when calling Bukkit/Paper APIs.
- Script errors should be reported in a way that server administrators and script authors can understand.
- Do not simply swallow exceptions. Hidden failures make script loading and server operation issues difficult to trace.
- If a class or method may be used like a public API, explain any compatibility impact in the PR.
- If you need to modify an auto-generated file, also check the source and process used to generate it.

## AI Agent Usage

We welcome AI-assisted contributions. Jetpack believes AI Agents can be useful tools for quickly exploring good ideas and refining implementations.

If you used an AI Agent, please add a short note in the PR message `Additional Notes`. If possible, include the tool and model you used. We would also love to know which tools and models were genuinely helpful or impressive.
