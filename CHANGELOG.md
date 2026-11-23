# Changelog

All notable changes to Folio are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- MIT license, third-party notices, `.gitattributes`, `.editorconfig` and a GitHub Actions build.
- README screenshot and project metadata in `pom.xml`.

## [0.1.0] - 2026-11-23

### Added
- Browse folders, drives and network shares with breadcrumb navigation, back/forward (including the
  mouse buttons) and a background network search.
- View and edit EPUB metadata, singly or for several books at once, with the raw OPF shown alongside.
- Save to the EPUB with a fast raw-copy rewrite and a `.bak` backup of the original (Ctrl+S).
- Rename files from a template of author, series and title tokens, with live previews.
- Dark theme with a neon accent; files are green when already named per the pattern and amber when
  they would be renamed.
- `--debug`, `--path` and `--help` command-line options.

[Unreleased]: https://github.com/wasootch/folio/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/wasootch/folio/releases/tag/v0.1.0
