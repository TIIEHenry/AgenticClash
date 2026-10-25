# Contributing to AgenticClash

## License

This project is licensed under GPL-3.0. By contributing you agree that your contribution is
licensed under the same terms. Contributions are not merged into any closed-source branch.

## Before you start

1. Read [AGENTS.md](AGENTS.md) and [docs/DOCUMENTATION.md](docs/DOCUMENTATION.md).
2. Check [dev/progress/status.md](dev/progress/status.md) for current work.
3. For behavior changes, write the plan under `dev/plans/` or the relevant `docs/systems/` page
   before coding.

## Code style

Open the project in Android Studio or IntelliJ IDEA and use the project code style profile:

`File` -> `Settings` -> `Editor` -> `Code Style` -> `C/C++ and Kotlin` -> `Scheme` -> `Project`

## Build and test

```bash
git submodule update --init --recursive
./gradlew :service:testMetaDebugUnitTest
./gradlew :design:testMetaDebugUnitTest
python3 scripts/check-docs-health.py
```

See the [build guide](docs/guides/getting-started/build.md) for signing and submodule details.

## Commits

- Keep each commit focused on one topic.
- Update the action layer in `dev/` and the knowledge layer in `docs/` when behavior or contracts
  change.
- `release.keystore` stays tracked and is the same file as upstream. Do not commit another keystore,
  `signing.properties`, `local.properties`, tokens, device identifiers, or real server addresses.
  Use reserved domains such as `example.com` in tests and documentation.

## Security issues

Do not open a public issue for a vulnerability. Report it privately to the maintainer through the
contact details in [SECURITY.md](SECURITY.md).
