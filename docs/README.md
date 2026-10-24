# Documentation

## Install and use

- [Project README](../README.md): CLI quick start, supported hardware, known limitations and rolling downloads.
- [foobar2000 user guide](../foobar/README.md): install, settings, controls, updates and the **physical M5 acceptance checklist**.
- [Android adapter](../mobile/README.md): mobile setup, renderer behavior and released features.
- [PyPI publishing](PUBLISHING.md): Python CLI distribution, Trusted Publishing and release recovery. The foobar component and APK use their separate GitHub release workflow.

## Development and maintenance

- [foobar2000 implementation notes](FOOBAR_PLUGIN.md): output pipeline, configuration semantics and design decisions; not another install guide.
- [Android vNext](ANDROID_VNEXT.md): **maintained Android hardware-check backlog**. Shipped UI features and pending M5 tests are marked separately.
- [Mobile design](../mobile/DESIGN.md): UI design decisions and ownership of visual tokens.
- [Testing](TESTING.md): automated checks versus manual speaker validation.
- [Integrations](INTEGRATIONS.md): external systems, persistence and trust boundaries.
- [Codebase concerns](CONCERNS.md): risks and accepted limitations.
- [Third-party notices](THIRD_PARTY_NOTICES.md): attribution and licensing boundaries.

## Protocol evidence and historical reference

- [Samsung WAM protocol](WAM_PROTOCOL.md): carefully preserved command observations on the physical M5; different playback paths must not be conflated.
- [Development status / lab journal](DEVELOPMENT_STATUS.md): dated experiments, decisions and failed approaches, **not** the live task queue.
- [Event listener](EVENT_LISTENER.md): isolated TCP `55001` diagnostics and event interpretation.
- [One-shot probes](../tools/wam-probes/README.md): research scripts behind protocol measurements, not ordinary user commands.
- [Archived Android design plans](superpowers/): historical design material; see Android vNext for remaining checks.

A merged change, a green CI build, a diagnostic smoke test and a physically heard full track are **different levels of evidence**. Label the date, branch and kind of test; prefer the latest measurement when older notes conflict. Keep hardware checklists in their owner documents instead of duplicating them in the historical journals.
