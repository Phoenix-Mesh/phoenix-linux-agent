# Phoenix Linux Node Agent

The Linux node agent for [Phoenix](https://github.com/Phoenix-Mesh/phoenix), a
lightweight orchestrator for heterogeneous edge devices.

The agent turns a Linux machine into a Phoenix edge node. It registers the
machine with Phoenix Core, reports its resources every five seconds, and
executes the tasks Core sends it. Placement, health, timeouts and retries are
decided by Core; the agent only reports and executes.

It speaks two transports:

| Transport            | Since  | How it connects                                                                      |
| -------------------- | ------ | ------------------------------------------------------------------------------------ |
| **MQTT/TLS**         | v0.3.0 | One outbound TLS connection to the broker; Core pushes work through it. Recommended. |
| **HTTP push** (v0.2) | v0.2.0 | Core calls the agent's `POST /execute` endpoint. Unchanged baseline.                 |

## Quick start (MQTT/TLS)

You need a Phoenix Core with MQTT enabled and its Mosquitto broker (see Core's
[`deploy/mosquitto`](https://github.com/Phoenix-Mesh/phoenix/tree/main/deploy/mosquitto)).
The full walkthrough is in [docs/mqtt-tls.md](docs/mqtt-tls.md).

1. Download `phoenix-linux-agent-v0.3.0.jar` from the
   [latest release](https://github.com/Phoenix-Mesh/phoenix-linux-agent/releases/latest)
   (Java 11 or newer), or [build it](#building-from-source).
2. Print this machine's device id. It is also its broker user name:

    ```bash
    java -jar phoenix-linux-agent-v0.3.0.jar --print-device-id
    ```

3. On the broker host, create the account with that id and copy `ca.pem` (the
   CA certificate only) to this machine:

    ```bash
    ./add-device.sh /etc/mosquitto/phoenix/passwd linux-…   # prints "<id> <password>"
    ```

4. Store the password (and Core's enrollment token, if Core uses one) in files
   only you can read, then start the agent:

    ```bash
    java -jar phoenix-linux-agent-v0.3.0.jar \
        --broker broker.lan \
        --ca ~/.phoenix/ca.pem \
        --password-file ~/.phoenix/broker-password \
        --enrollment-token-file ~/.phoenix/enrollment-token
    ```

5. The machine appears in the **NODES** panel of the Core console. Press `D`
   there to dispatch a task.

To keep it running across reboots, use the systemd unit in
[`deploy/systemd`](deploy/systemd/phoenix-linux-agent.service).

## Usage

```text
java -jar phoenix-linux-agent-<version>.jar --broker URI [options]         MQTT/TLS
java -jar phoenix-linux-agent-<version>.jar [CORE_URL] [CALLBACK_PORT]    HTTP push (v0.2)
java -jar phoenix-linux-agent-<version>.jar --print-device-id | --version | --help
```

### MQTT/TLS options

| Option                         | Meaning                                                                                       | Default                       |
| ------------------------------ | --------------------------------------------------------------------------------------------- | ----------------------------- |
| `--broker URI`                 | `host`, `host:port`, `ssl://host:port` or `mqtts://host:port`; the host must be in the broker certificate | port `8883`                   |
| `--ca FILE`                    | CA certificate(s) the broker certificate is signed with (PEM); exactly these are trusted     | the Java runtime's CA store   |
| `--password-file FILE`         | broker password; or the `PHOENIX_MQTT_PASSWORD` environment variable                          | required                      |
| `--enrollment-token-file FILE` | Core's enrollment token; or `PHOENIX_MQTT_ENROLLMENT_TOKEN`                                   | none                          |
| `--device-id-file FILE`        | where the device id is kept                                                                   | `~/.phoenix_linux_device_id`  |
| `--display-name NAME`          | node name shown by Core                                                                       | the host name                 |
| `--capability TAG`             | scheduling tag announced at registration; repeatable                                          | none                          |
| `--topic-prefix PREFIX`        | must match Core's `phoenix.mqtt.topicPrefix`                                                  | `phoenix`                     |
| `--validation-hold SECONDS`    | validation runs only: each task waits this long after it started (0-300)                     | `0`                           |

Plain `tcp://` is accepted only for a broker on the same machine. Exit codes:
`0` after `Ctrl+C` or `SIGTERM`, `1` when the agent gave up (refused
credentials, untrusted broker certificate, registration refused by Core), `2`
for unusable options.

### HTTP push options

| Argument        | Meaning                                                                | Default                 |
| --------------- | ---------------------------------------------------------------------- | ----------------------- |
| `CORE_URL`      | Address of Phoenix Core's HTTP gateway, as reachable from this machine | `http://127.0.0.1:8080` |
| `CALLBACK_PORT` | Port this agent listens on for tasks pushed by Core                    | `9090`                  |

The HTTP agent is unchanged from v0.2.1; see [HTTP push](#http-push-v02) below.

## What the agent does (MQTT/TLS)

It implements Phoenix's [MQTT/TLS node-agent protocol, version 1](https://github.com/Phoenix-Mesh/phoenix/blob/main/docs/mqtt-agent-protocol.md):

1. Connects to the broker over TLS, verifying the broker certificate and its
   host name, with the device id as client id and user name, a persistent
   session, and a retained last will (`offline`) on its status topic.
2. Registers with a fresh request id (and the enrollment token), and keeps the
   session token Core returns. An unanswered registration is sent again every
   10 s, so the agent can start before Core.
3. Sends a heartbeat every 5 s (QoS 0) with real telemetry.
4. For each task: acknowledges it at once, reports `started`, runs it, and
   reports the result, always echoing the attempt id.
5. Registers again when Core restarts or asks it to, and reconnects on its
   own after a lost connection.

It never decides whether the node is `ONLINE`, `DEGRADED` or `OFFLINE`, and
never retries a task: Core's health monitor and recovery policy do, exactly
as for every other node.

### Telemetry

| Field             | Source                                                                         |
| ----------------- | ------------------------------------------------------------------------------ |
| CPU utilization   | `/proc/stat`, since the previous heartbeat; `-1` (unknown) if unreadable       |
| Available RAM     | `MemAvailable` in `/proc/meminfo` (`MemFree + Buffers + Cached` before 3.14)  |
| Active tasks      | tasks currently executing                                                      |
| Queue length      | tasks received and waiting for the worker                                      |
| Network latency   | round trip of the registration request through the broker to Core and back    |
| Energy cost       | fixed heuristic `4.5`, not a measurement                                       |

Registration reports the host name, CPU architecture, core count and total
memory. Nothing is invented: a value the kernel does not provide is reported
as unknown, or the agent refuses to start (total memory, which Core requires).

### Tasks

Tasks run one at a time. The agent executes `Monte-Carlo-Pi` with the
requested number of samples (default 2,000,000, at most 200,000,000). Any
other task type, or an invalid sample count, is reported as `FAILED` with the
reason in `outputData`.

Delivery is at least once, so the agent is idempotent:

- every delivery of a task is acknowledged, duplicates included;
- an attempt id is never run twice: a duplicate of a finished attempt gets its
  known result again, a duplicate of a running attempt only its acknowledgement;
- an attempt Core cancelled is skipped, or stopped if running, and sends no result;
- a task that arrives before the registration answer waits for it.

Handled and cancelled attempt ids are remembered in memory (the last 1,024).

### Device identity

The device id (`linux-<uuid>`) is created on first start and stored in
`~/.phoenix_linux_device_id`, the same file the HTTP agent uses, so a machine
keeps one identity across restarts and across transports: Core gives it the
same node id over HTTP and MQTT. The file is per user; use `--device-id-file`
for a service account.

### Example output

```text
[10:02:11] [LinuxNodeAgent] Phoenix Linux Node Agent v0.3.0 (MQTT/TLS, protocol v1)
[10:02:11] [LinuxNodeAgent] Device id linux-6c0f… (broker user name and client id; stored in /home/pi/.phoenix_linux_device_id)
[10:02:11] [LinuxNodeAgent] Connecting to ssl://broker.lan:8883 as linux-6c0f… (TLS)...
[10:02:12] [LinuxNodeAgent] Connected to the broker.
[10:02:12] [LinuxNodeAgent] Registered with Phoenix Core: node 3e1d… (device linux-6c0f…, registration round trip 21.4 ms, heartbeat every 5 s).
[10:02:12] [LinuxNodeAgent] Heartbeat sent: cpu=3.2% freeRam=6120MB activeTasks=0 queued=0 rtt=21.4ms energy=4.5
[10:02:30] [LinuxNodeAgent] Received task task-001 (attempt 1, id 7b1adffa), executing Monte-Carlo (samples=2000000)...
[10:02:30] [LinuxNodeAgent] Task task-001 done: Computed Monte-Carlo pi ~= 3.1418 (38ms)
```

## HTTP push (v0.2)

Run with Core's HTTP address and a callback port, as before:

```bash
java -jar phoenix-linux-agent-v0.3.0.jar http://192.0.2.10:8080 9090
```

The agent registers with `POST /register` (announcing `attempt-id`), sends
`POST /heartbeat` every 5 seconds, and executes tasks Core pushes to its
`POST /execute` endpoint. The wire format is Core's
[push agent protocol](https://github.com/Phoenix-Mesh/phoenix/blob/main/docs/linux-agent-protocol.md).
Core must reach the machine on the callback port. Limitations of this
transport, which MQTT/TLS does not have: start Core first (it registers once
at startup), restart the agent after restarting Core, and the callback
endpoint is plain HTTP without authentication.

## Compatibility

| Agent  | Phoenix Core                     | Transports                                 |
| ------ | -------------------------------- | ------------------------------------------ |
| v0.3.0 | with MQTT/TLS (`main`, v0.3.x)   | MQTT/TLS (protocol v1); HTTP push          |
| v0.3.0 | v0.2.0, v0.2.1                   | HTTP push                                  |
| v0.2.1 | v0.2.0, v0.2.1, `main`           | HTTP push                                  |

## Validating a Linux node

[docs/mqtt-tls-validation.md](docs/mqtt-tls-validation.md) is the runbook for
Phoenix issue [#24](https://github.com/Phoenix-Mesh/phoenix/issues/24): every
scenario of the test matrix, the commands to run and the log lines to expect.

## Known limitations

- Tasks run one at a time, and `Monte-Carlo-Pi` is the only workload.
- Energy cost is a fixed heuristic; network latency is the registration round
  trip, refreshed at each registration.
- Handled attempt ids are kept in memory, so duplicate suppression does not
  survive a restart of the agent (Core still ignores a second result).

## Building from source

Requires JDK 17 or newer to build; the jar runs on Java 11 or newer.

```bash
./gradlew build          # compiles, runs the tests, builds the jar
java -jar build/libs/phoenix-linux-agent-v0.3.0.jar --help
```

The Mosquitto integration tests run when `mosquitto`, `mosquitto_passwd` and
`openssl` are installed, and are skipped otherwise.

## Repository layout

```text
src/main/java/com/phoenix/linuxagent/
    Main.java                 command line: chooses the transport
    LinuxNodeAgent.java       HTTP push agent (v0.2)
    mqtt/                     MQTT/TLS agent: protocol, Paho link, TLS, telemetry
src/test/java/                unit tests and Mosquitto integration tests
deploy/systemd/               service unit
docs/                         setup guide and validation runbook
```

## Related repositories

- [Phoenix Core](https://github.com/Phoenix-Mesh/phoenix): the orchestrator.
- [Phoenix Android Agent](https://github.com/Phoenix-Mesh/phoenix-android-agent):
  the Android node agent.

## License

This project is licensed under the
[Apache License, Version 2.0](LICENSE).
