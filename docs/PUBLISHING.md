# Publishing WAM Bridge on PyPI

Distribution: `wambridge` (formerly `trvny-wambridge`). Python imports and CLI commands remain `wambridge`, `wambridge-control`, `wambridge-events`, `wambridge-pcm` and `wambridge-share`.

The distribution contains the Python CLI, not the Android APK or foobar2000 component.

## One release pipeline

[Release](../.github/workflows/release.yml) owns delivery:

- Push to `main` touching the configured release workflow paths: refresh rolling GitHub Release `latest` (APK, foobar component, source, Android R8 mappings). Do not republish the same Python version to PyPI.
- `workflow_dispatch` on `main` with **version** matching `pyproject.toml` (e.g. `0.0.5`): build the Python wheel and sdist, smoke-test the wheel, create a fixed `vX.Y.Z` GitHub Release only when the APK, foobar and Python builds pass, then publish both Python files to PyPI.
- `pyproject.toml` provides the canonical version. Fixed releases with mismatched versions fail before publication. `tests/test_component_version.py` verifies version mirrors. Change mirrors together before the next fixed release.

The PyPI job uses GitHub OIDC and `uv publish --check-url`; no stored API token. The existing GitHub Actions `pypi` environment gates the job. Retrying the **same job with its original artifacts** skips already-published identical files.

## One-time PyPI setup

Register a *pending Trusted Publisher* at https://pypi.org/manage/account/publishing/:

| Setting | Value |
| --- | --- |
| PyPI project | `wambridge` |
| GitHub owner | `travnie` |
| Repository | `wambridge` |
| Workflow filename | `release.yml` |
| GitHub environment | `pypi` |

The `pypi` GitHub environment already exists. Restrict its deployment branches to `main`; add an approval gate if desired. First OIDC upload creates the project if PyPI permits the name. A public PyPI API `404` is not proof the name can be registered (it may be reserved).

The previously published `trvny-wambridge` package remains independent. A metadata rename neither deletes it nor silently migrates users.

After the first successful upload:

```sh
uv tool install wambridge
wambridge --help
```

## Recovery

- OIDC failure: check project, owner, repository, workflow filename and environment in the PyPI publisher configuration. Rerun the failed PyPI job using the exact built artifacts.
- Incomplete upload: retry the same job; `uv publish --check-url` skips existing identical files. Never rebuild a different artifact for an already-published version.
- New version: change `pyproject.toml` and its version mirrors before triggering a new fixed release.

R8 mappings for **fixed versioned releases** are retained with their corresponding APK.
Rolling `latest` retains only the current APK's mapping; superseded rolling-only mappings
are discarded, not archived on every push. The pre-policy historical mappings are
preserved once in `wambridge-r8-legacy-20261103.zip` on `latest`, with a checksum
manifest. If the rolling release is recreated, restore this snapshot separately;
the workflow never generates new legacy archives. Never remove that archive
without migrating its contents first.
