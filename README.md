<div align="center">

<img src="./.github/assets/jetpack.png" alt="Jetpack" width="480"><br>

Jetpack is a Minecraft plugin that lets you write and run scripts on your Paper server.
Build features in a small scripting language without recompilation or restarts.

</div>

## Features

- Write server logic in `.jet` files and load them live, with no Java build or restart
- Register commands, react to server events, and run timed intervals
- Reach the full Paper/Bukkit API through a native bridge
- Statically typed, so errors are caught before a script runs

## Requirements

- Paper 1.21.10 or newer
- Java 21 or newer

## Installation

1. Download `Jetpack.jar` from [Modrinth](https://modrinth.com/plugin/jetpackmc) or [GitHub Releases](https://github.com/JetpackMC/Jetpack/releases).
2. Place the jar in your server's `plugins/` directory.
3. Start or restart the server.

On first run, Jetpack creates its configuration file and script directory automatically.
Add `.jet` files under `plugins/Jetpack/scripts/`, then load them with `/jetpack reload`.

## Ecosystem

- [Documentation](https://github.com/JetpackMC/docs): setup, syntax, scripts, and module reference
- [Language Server](https://github.com/JetpackMC/language-server): VS Code extension with highlighting, diagnostics, and completion for `.jet` files
- [Skill](https://github.com/JetpackMC/skills): agent skill for writing, reviewing, and debugging `.jet` scripts

## Contributing

Thank you for contributing to the Jetpack project.
If you would like to contribute, please read [CONTRIBUTING.md](CONTRIBUTING.md).

![Contributors](https://contrib.rocks/image?repo=JetpackMC/Jetpack)

## License

The Jetpack project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
