# RecoveryClass persistence

This work fixes the durable execution boundary so an attempt records the recovery semantics declared by the operation it executes.

The executor must never persist a broader replay policy than the operation actually has. In particular, an `UNSAFE` tool call must remain `UNSAFE` in the attempt record so crash recovery can classify the interrupted effect as indeterminate rather than replay it automatically.

Implementation and tests are being added in the associated draft PR.