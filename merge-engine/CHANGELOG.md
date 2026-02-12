# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-02-12

### Added

- Four-stage merge conflict resolution pipeline:
  - Pattern-based DSL rules (7 patterns: whitespace-only, identical change, both-add, one-empty, prefix/suffix, import union, adjacent edit)
  - Structured merge via tree-sitter CSTs with three-way tree amalgamation
  - Version Space Algebra (VSA) candidate enumeration and ranking
  - Search-based resolution with parent similarity fitness function
- Tree-sitter language support: Rust, JavaScript, TypeScript, Python, Java, Go, C, C++, Kotlin, TOML, YAML
- CLI binary usable as a git custom merge driver (`merge-engine %O %A %B %P`)
- `--stdin` mode for reading conflict markers from stdin
- `--check` mode for dry-run conflict analysis
- Library API: `Resolver::resolve_file()` and `Resolver::resolve_conflict()`
- 40 unit tests across all modules
- 27 integration tests including 7 real-world fixtures from maceip/MediaMaid merge history
