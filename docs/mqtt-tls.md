# Connecting the Linux agent over MQTT/TLS

The agent speaks two transports. **HTTP push (v0.2)** is the original
baseline and is unchanged. **MQTT/TLS** implements the Phoenix MQTT
node-agent protocol, version 1 (Phoenix Core:
[`docs/mqtt-agent-protocol.md`](https://github.com/Phoenix-Mesh/phoenix/blob/main/docs/mqtt-agent-protocol.md)).
The machine opens one outbound TLS connection to the broker, and Phoenix Core
pushes work to it through the broker. Unlike HTTP push, Core never connects
to the machine, so no port has to be opened on it.

```text
Phoenix Core ── loopback ──> Mosquitto ── TLS :8883 <── Linux agent
(control node)               (control node)            (any Linux machine)
```

## 1. Prepare the broker (on the control node)

Use the files in Phoenix Core's
[`deploy/mosquitto`](https://github.com/Phoenix-Mesh/phoenix/tree/main/deploy/mosquitto)
(see its README). Two details matter for an agent:

1. **The certificate must name the address the agent connects to.** If the
   agent uses `broker.lan`, or the IP `192.168.1.20`, include it:

    ```bash
    ./gen-certs.sh /etc/mosquitto/phoenix/certs broker.lan 192.168.1.20
    ```

    The agent checks the broker's host name against the certificate during
    the TLS handshake. A name or IP missing from the certificate is refused.

2. **The broker user name must be the machine's device id.** Print it on the
   Linux machine (it is created on first use and then kept):

    ```bash
    java -jar phoenix-linux-agent-v0.3.0.jar --print-device-id
    ```

    and create the account with that exact id on the control node:

    ```bash
    ./add-device.sh /etc/mosquitto/phoenix/passwd linux-6c0f…   # prints "<id> <password>"
    sudo systemctl reload mosquitto
    ```

    A machine that already ran the HTTP agent keeps its device id (the same
    `~/.phoenix_linux_device_id` file), so Core keeps its node identity too.

## 2. Put the CA and the secrets on the Linux machine

Copy `ca.pem` (only the CA certificate, **never** `ca.key`). Write the broker
password and, if Core was started with one, Core's enrollment token into
files only the agent's user can read:

```bash
install -d -m 0700 ~/.phoenix
install -m 0644 ca.pem ~/.phoenix/ca.pem
printf '%s\n' '<password from add-device.sh>' > ~/.phoenix/broker-password
printf '%s\n' '<Core enrollment token>' > ~/.phoenix/enrollment-token
chmod 600 ~/.phoenix/broker-password ~/.phoenix/enrollment-token
```

Instead of files, the agent also reads `PHOENIX_MQTT_PASSWORD` and
`PHOENIX_MQTT_ENROLLMENT_TOKEN` from the environment. Secrets never appear in
its output.

## 3. Start the agent

```bash
java -jar phoenix-linux-agent-v0.3.0.jar \
    --broker broker.lan \
    --ca ~/.phoenix/ca.pem \
    --password-file ~/.phoenix/broker-password \
    --enrollment-token-file ~/.phoenix/enrollment-token
```

It prints `Connected to the broker.`, then `Registered with Phoenix Core:
node <nodeId> …` and a heartbeat line every 5 seconds. Tasks dispatched from
the Core console run on the machine. The agent may be started before Core: it
keeps sending its registration every 10 s until Core answers.

`Ctrl+C` (or `SIGTERM`) stops it cleanly: it publishes `offline` on its
status topic and disconnects. If the process dies instead, the broker
publishes the same message as its last will. Either way, whether the node is
`ONLINE`, `DEGRADED` or `OFFLINE` is decided by Phoenix Core from heartbeats,
and shown there.

### As a service

[`deploy/systemd/phoenix-linux-agent.service`](../deploy/systemd/phoenix-linux-agent.service)
runs the agent as a dedicated `phoenix` user, with the secrets in
`/etc/phoenix-linux-agent/` and the device id in
`/var/lib/phoenix-linux-agent/device-id`. The installation steps are in the
file's header. `journalctl -u phoenix-linux-agent -f` shows the agent's log.

## Troubleshooting

| Output                                                                    | Cause                                                                                   |
| ------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| *the broker refused this device's user name or password*                  | wrong password, or no broker account for this device id (`--print-device-id`)           |
| *TLS failed: the broker certificate is not trusted (check the CA file)*   | wrong or missing `--ca`                                                                 |
| *the broker certificate does not match the host name in ssl://…*         | the address in `--broker` is not in the certificate; regenerate it with that name or IP |
| *Phoenix Core refused the registration: enrollment_rejected*              | missing or wrong enrollment token                                                       |
| *Broker not reachable (…); retrying...*                                   | wrong address or port, broker down, or a firewall; the agent keeps retrying             |
| *No answer from Phoenix Core yet; sending the registration again.*        | the broker is reachable, but Core is not running or not connected to it                 |
| *Plain tcp:// is only allowed for a broker on this machine*               | use `ssl://` (the default) for a remote broker                                          |

The first four stop the agent with exit code 1; fix the cause and start it
again (the systemd unit retries every 30 s on its own).

## Security notes

- TLS 1.2 or 1.3 with host name verification, against the CA file you give
  (exactly those certificates are trusted) or the Java runtime's CA store.
- The device id is the broker user name and MQTT client id, so the broker ACL
  keeps every machine on its own topics; Core takes the device id from the
  topic, never from a message body.
- The enrollment token stops a machine that has broker credentials from
  registering without Core's consent. The session token Core issues at each
  registration fences out stale agent copies.
