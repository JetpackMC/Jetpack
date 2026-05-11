# Contributing
Thank you for your interest in contributing to Jetpack!

## Table of Contents
- [Contributing](#contributing)
  - [Table of Contents](#table-of-contents)
  - [Branch](#branch)
    - [Branch Naming Convention](#branch-naming-convention)
    - [Branch Naming Guidelines](#branch-naming-guidelines)
    - [Reserved Branches](#reserved-branches)
  - [Pull Request](#pull-request)
    - [PR Title Guidelines](#pr-title-guidelines)
    - [PR Description Guidelines](#pr-description-guidelines)
    - [AI Agent Usage](#ai-agent-usage)

## Branch

### Branch Naming Convention
We follow a specific naming convention for branches to maintain clarity and organization

|Prefix|Purpose|
|:-:|:--|
|`bugs/`|Bug fixes|
|`features/`|New features|
|`refactor/`|Code refactoring|
|`hotfix/`|Urgent critical fixes|
|`chore/`|Maintenance tasks and items not covered by the categories above|
|`agents/`|Mostly AI-agent-driven contributions; please use `agents/Prefix/...`|

### Branch Naming Guidelines
- Specify what feature or bug is being addressed
- Use lowercase with hyphens to separate words
- Clearly identify the specific component, feature, or issue
- If most of the work came from an AI Agent (often called Vibe Coding), please use `agents/Prefix/...` where `Prefix` matches the change category (for example, `features`, `bugs`, `refactor`, `hotfix`, `chore`)

### Reserved Branches
- `develop` is reserved for maintainers and is not available for external contributors
- It is used for integration testing and small development-stage changes
- `develop` changes are fast-moving and may be unstable


## Pull Request

### PR Title Guidelines
- Focus on the main functionality being modified
- Briefly mention other modified features

### PR Description Guidelines
- Describe the impact of the changes
- Explain the reasoning behind the modifications
- Mention issue numbers for bug/issue fixes
- Provide clear context without unnecessary fluff

### AI Agent Usage
- We love AI-assisted contributions
- If you used an AI Agent, please add a short note in the PR message `Additional Notes`. If possible, include the tool and model you used. Some models can be genuinely impressive, and we'd love to know what helped you!
