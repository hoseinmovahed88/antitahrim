# wbrelay

The Bale Meet joiner from
[kulikov0/whitelist-bypass-iran](https://github.com/kulikov0/whitelist-bypass-iran),
copied without changes from its `relay/` directory. MIT licensed; see `LICENSE`.
The upstream commit is in `UPSTREAM_COMMIT`. Brought in with the repository
owner's explicit approval.

Only the packages the joiner needs are kept: `common`, `livekit`, `tunnel`,
`bale` and `pion/headless-joiner-common`. The creator, desktop, iOS and
Android front ends are left out; `azadcore/bale.go` is our Android front end.

To update, copy the same directories from a newer upstream commit and run
`go mod tidy` in `tools/azadcore`.
