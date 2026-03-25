# Contributing to MobileClaw

Thank you for your interest in contributing to MobileClaw. This guide covers the development workflow and standards we follow.

## Prerequisites

- **Android Studio** Ladybug (2024.2.1) or later
- **JDK 17** (bundled with Android Studio or installed separately)
- **Kotlin 2.2.0** (managed via Gradle version catalog)
- **Android SDK** with API 35 (compile) and API 29+ device/emulator
- **An AI API key** for testing cloud inference (Anthropic, OpenAI, Google, etc.) — or use on-device Gemma 4 (no key needed)

## Getting Started

1. **Fork** the repository on GitHub.
2. **Clone** your fork:
   ```bash
   git clone https://github.com/<your-username>/MobileClaw.git
   cd MobileClaw
   ```
3. **Open** the project in Android Studio and let Gradle sync.
4. **Create a branch** for your change:
   ```bash
   git checkout -b feat/your-feature-name
   ```

## Development Workflow

1. Make your changes on a feature branch.
2. Write or update unit tests for any new or changed logic.
3. Run the full test suite before submitting:
   ```bash
   ./gradlew testDebugUnitTest
   ```
4. Run lint:
   ```bash
   ./gradlew ktlintCheck
   ```
5. Build to verify compilation:
   ```bash
   ./gradlew assembleDebug
   ```
6. Push your branch and open a Pull Request against `main`.

## Code Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
- We use **ktlint** for formatting enforcement. Run `./gradlew ktlintFormat` to auto-fix issues.
- Use 4-space indentation (no tabs).
- Maximum line length: 120 characters.
- Prefer `val` over `var`. Prefer immutable data structures.
- Use explicit types for public API return values.

## Commit Message Format

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>

<optional body>

<optional footer>
```

**Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`, `ci`

**Examples:**
```
feat(tools): add BluetoothTool for device pairing
fix(agent): handle empty tool result from Claude API
docs(readme): update architecture diagram
test(skills): add unit tests for SkillsManager parsing
```

## Testing Requirements

- All new tools must have unit tests.
- All new ViewModels must have unit tests.
- Tests use JUnit 5, MockK, Turbine, and Truth.
- Target: maintain or improve existing test coverage.

## Pull Request Checklist

Before submitting your PR, verify:

- [ ] Code compiles without errors (`./gradlew assembleDebug`)
- [ ] All existing tests pass (`./gradlew testDebugUnitTest`)
- [ ] New tests are added for new functionality
- [ ] Lint passes (`./gradlew ktlintCheck`)
- [ ] Commit messages follow Conventional Commits format
- [ ] PR description explains what and why

## Adding a New Tool

1. Create a new class implementing `AndroidTool` in `app/src/main/java/ai/affiora/mobileclaw/tools/`.
2. Define `name`, `description`, and `parameters` (JSON Schema).
3. Implement the `execute()` method.
4. Register the tool via Hilt in the tool module.
5. Add unit tests in `app/src/test/java/ai/affiora/mobileclaw/tools/`.
6. Update the tool table in `README.md`.

## Adding a New Skill

1. Create a directory under `app/src/main/assets/skills/built-in/` (or `vertical/`).
2. Add a `SKILL.md` file with YAML frontmatter (`name`, `description`, `version`, `author`, `tools_required`).
3. Write the skill prompt in the Markdown body.
4. Update the skills list in `README.md`.

## Reporting Issues

Use [GitHub Issues](https://github.com/ChenKuanSun/mobileClaw/issues) with the appropriate template. For security vulnerabilities, see [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
