# Documentation

## Current state and next steps

- [Project README](../README.md): what ships, installation and rolling release.
- [Android vNext](ANDROID_VNEXT.md): **maintained Android hardware-check backlog**, including tests still needed on the M5.
- [foobar2000 component](FOOBAR_PLUGIN.md): implemented features, transport constraints and missing foobar features.
- [Codebase concerns](CONCERNS.md): current risks and accepted limitations.

## Measurement and historical reference

- [Development status / lab journal](DEVELOPMENT_STATUS.md): historical experiments, decisions and measurements, **not** the live task queue.
- [Samsung WAM protocol](WAM_PROTOCOL.md): commands and behavior measured on a physical M5.
- [Event listener](EVENT_LISTENER.md): TCP `55001` events and diagnostics.
- [Android adapter](../mobile/README.md): mobile architecture and existing features.

A merged PR, green CI and a physical speaker test are different evidence levels.
Keep each status labelled; use the latest dated hardware measurement when older notes conflict.
Do not duplicate the Android checklist in the long development journal.
