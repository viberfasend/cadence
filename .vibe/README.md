# Mistral Vibe Configuration

This directory contains Mistral Vibe-specific configuration files for the Cadence project.

## Files

- **`.vibeignore`** - Specifies files and directories that Mistral Vibe should ignore
- **`settings.json`** - Global settings for Mistral Vibe behavior

## Project-Specific Settings

This Android project has the following Mistral Vibe optimizations:

### Ignored Files
- Build directories (`build/`, `app/build/`)
- IDE directories (`.idea/`, `*.iml`, `*.ipr`)
- Local properties and signing files
- Gradle cache directories
- Generated files
- Test results and reports
- macOS and Windows system files

### Recommended Workflow

1. **Code Review**: Use Mistral Vibe for comprehensive code reviews
2. **Feature Implementation**: Request implementation of new features with detailed specifications
3. **Bug Fixing**: Ask for analysis and fixes for specific issues
4. **Testing**: Request test cases and verification strategies

### Android-Specific Guidance

When working with this Android project, Mistral Vibe should:
- Follow the existing code style and architecture patterns
- Use Kotlin best practices
- Respect the single ViewModel architecture
- Maintain proper separation of concerns (domain/data/ui)
- Follow the existing naming conventions
- Preserve the Material 3 design language

## Usage

Mistral Vibe will automatically use these configuration files when working in this repository.

### Custom Commands

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run connected tests (requires device)
./gradlew connectedDebugAndroidTest
```

## Integration with Existing Files

This repository already has:
- **`CLAUDE.md`** - Guidance for Claude Code
- **`.claude/settings.local.json`** - Claude-specific settings

Both Mistral Vibe and Claude Code configurations coexist peacefully in this repository.

## Version Control

All `.vibe/` files are tracked in git to ensure consistent Mistral Vibe behavior across all environments and contributors.
