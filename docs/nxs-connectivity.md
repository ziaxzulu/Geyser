# NXS connectivity checks and player offers

Geyser logs each new maintenance check with its region, IP family, method,
attempted public endpoint (when known), outcome, and check time. The same result
returned by later heartbeats is not logged again. Publication changes are logged
separately. These checks establish transport; they do not test gameplay.

| Result | Assisted joins enabled | Assisted joins disabled |
| --- | --- | --- |
| Transport established | Keep assistance available. | Offer or restore the tested endpoint. |
| Transport failed | Keep the family available; warn that clients on similar networks are unlikely to connect. | Withhold the failed public endpoint unless another region has a successful check. Continue testing for recovery. |
| Unknown or unavailable | No reachability decision. | No reachability decision. |

A public client or one behind less restrictive NAT may connect where a regional
assisted probe failed. This is a possibility, not a guarantee or a NAT diagnosis.
Per-join STUN discovers the public peer for that attempt. The peer appears in the
check log; it is not promoted into a long-lived maintained candidate. Private
addresses only help clients with a route to that LAN/VPN. An expired result alone
does not restore a withdrawn public endpoint, and existing players stay connected.

## What to try next

All NXS settings below are under `bedrock.signaling.nxs` in Geyser configuration.
Use the actual NetherNet UDP gameplay port shown in the console (commonly 19133),
not the Java backend TCP port.

- For a direct IPv4 failure, allow inbound UDP in the host firewall and forward
  the gameplay UDP port through each router/NAT. With a fixed public mapping,
  set `advertise-addresses` to its numeric public address and external UDP port.
  This list is complete: include any desired IPv6 endpoint too. Configuring the
  address does not create a firewall rule or forwarding rule.
- If automatic discovery has no public candidate, keep `advertise-addresses`
  empty and try `maintained-candidates: true`. This maintains a STUN mapping on
  the gameplay socket; it cannot guarantee inbound reachability.
- To allow reciprocal ICE assistance, set `assisted-joins: true` and
  `control-transport: auto`. Per-join discovery replaces background warming.
  Failed probes do not silently turn assistance on or off.
- Behind CGNAT or multiple restrictive NATs, forwarding on one router may not
  suffice. Request a public IPv4 address from the ISP, or enable public IPv6.
- For IPv6 failure, check global routing and allow inbound gameplay UDP in the
  host and router firewalls. IPv4 port forwarding is not the IPv6 remedy.
- For inconclusive/unavailable checks, inspect provider/control connectivity and
  keep `diagnostic-admission: true`, then wait for another completed check.
  With diagnostics disabled, Geyser warns that it cannot verify the offered paths.

`maintained-candidates: false` disables warming, while offer decisions and recovery
checks remain active. Explicit `advertise-addresses` entries are eligible targets,
not an override of failed reachability checks. Configuration changes require a
Geyser restart; firewall/routing changes are picked up by subsequent checks.
