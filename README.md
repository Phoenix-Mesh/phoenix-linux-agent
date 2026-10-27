# Phoenix Linux Node Agent

The Linux node agent for [Phoenix](https://github.com/Phoenix-Mesh/phoenix), a
lightweight orchestrator for heterogeneous edge devices.

The agent turns a Linux machine into a Phoenix edge node. It registers the
machine with Phoenix Core, reports its resources every five seconds, and
executes the tasks Core pushes to it. Placement, health, timeouts and retries
are decided by Core; the agent only reports and executes.

## Quick start

1. Start Phoenix Core on the control machine (see the
   [Core README](https://github.com/Phoenix-Mesh/phoenix#running-the-core)).
2. Download `phoenix-linux-agent-<version>.jar` from the
   [latest release](https://github.com/Phoenix-Mesh/phoenix-linux-agent/releases/latest),
   or [build it](#building-from-source).
3. Run it on the Linux machine with Core's address and a free port for Core to
   call back on:

    ```bash
    java -jar phoenix-linux-agent-v0.2.1.jar http://192.0.2.10:8080 9090
    ```

4. The machine appears in the **NODES** panel of the Core console. Press `D`
   there to dispatch a task; it runs on this machine.

## Usage

```text
java -jar phoenix-linux-agent-<version>.jar [CORE_URL] [CALLBACK_PORT]
```

| Argument        | Meaning                                                                | Default                 |
| --------------- | ---------------------------------------------------------------------- | ----------------------- |
| `CORE_URL`      | Address of Phoenix Core's HTTP gateway, as reachable from this machine | `http://127.0.0.1:8080` |
| `CALLBACK_PORT` | Port this agent listens on for tasks pushed by Core                    | `9090`                  |

Start Core first. The agent registers once at startup; see
[known limitations](#known-limitations).

Stop the agent with `Ctrl+C`.

## Network requirements

Both directions must work:

- **Agent → Core:** this machine reaches `CORE_URL` (Core's port, normally `8080`).
- **Core → agent:** Core reaches this machine on `CALLBACK_PORT` (normally `9090`).
  Core uses the source address of the registration request together with the
  announced callback port, so register over the interface Core can reach.

Allow the callback port through the firewall, for example
`sudo ufw allow 9090/tcp`. Do not use `127.0.0.1` as `CORE_URL` when Core runs
on another machine.

## What the agent does

On startup it:

1. starts its callback server on `CALLBACK_PORT` (`POST /execute`);
2. registers with Core (`POST /register`);
3. sends a heartbeat every 5 seconds (`POST /heartbeat`);
4. executes each task Core pushes and returns the result in the same response.

The wire format is specified in Core's
[push agent protocol](https://github.com/Phoenix-Mesh/phoenix/blob/main/docs/linux-agent-protocol.md).

## Known limitations

- **Start Core first.** Registration happens once at startup. If Core is not
  reachable then, the agent prints the error but keeps running without
  registering; stop it and start it again.
- **Restart the agent after restarting Core.** The agent does not re-register,
  so after a Core restart its heartbeats are refused (`unknown_device`).
- **No authentication.** The callback endpoint accepts tasks from anyone who
  can reach the port, over plain HTTP. Use it on a trusted LAN only.
- **One task at a time.** Pushed tasks are executed one after another.
- **One workload.** Every task runs Monte-Carlo π; the task type is not checked,
  and every completed task reports `SUCCESS`.
- Energy cost is a fixed heuristic, not a hardware measurement.

## Building from source

Requires a JDK 11 or newer. From the repository root:

```bash
javac --release 11 -d build/classes com/phoenix/linuxagent/LinuxNodeAgent.java
jar --create --file build/phoenix-linux-agent.jar \
    --main-class com.phoenix.linuxagent.LinuxNodeAgent -C build/classes .
java -jar build/phoenix-linux-agent.jar http://192.0.2.10:8080 9090
```

Or run the source file directly, without a separate build step:

```bash
java com/phoenix/linuxagent/LinuxNodeAgent.java http://192.0.2.10:8080 9090
```

## Related repositories

- [Phoenix Core](https://github.com/Phoenix-Mesh/phoenix): the orchestrator.
- [Phoenix Android Agent](https://github.com/Phoenix-Mesh/phoenix-android-agent):
  the Android node agent.
