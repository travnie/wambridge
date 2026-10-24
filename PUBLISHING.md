# Publishing WAMBridge on PyPI

PyPI project: `trvny-wambridge`, commands: `wambridge`, `wambridge-control`, `wambridge-events`, `wambridge-pcm`, `wambridge-share`.

This distribution is the **Python command-line bridge**, not the foobar2000 plugin. The foobar component keeps its own prebuilt `.fb2k-component` releases and review/publication process in `foobar/`.

## Build/check

Run [Publish WAMBridge to PyPI](.github/workflows/publish-pypi.yml) with **publish = false** for a validated wheel and source distribution.

## First release

1. Sign in to PyPI and register a pending Trusted Publisher at https://pypi.org/manage/account/publishing/: project `trvny-wambridge`, owner `travnie`, repository `wambridge`, workflow `publish-pypi.yml`, environment `pypi`.
2. Configure GitHub's `pypi` environment with approval protection where possible.
3. After review/merge and a successful build run, run the workflow on `main` with **publish = true**. Update the `pyproject.toml` version before every later release.

Then:

```sh
uv tool install trvny-wambridge
wambridge --help
```

The bridge requires a reachable compatible Samsung WAM device. The foobar output component has been hardware-tested on M5; other models are experimental.
