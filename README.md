# Phoenix Linux Node Agent v0.2

Standalone Linux node agent for the Phoenix HTTP node-agent milestone.

The agent connects to a running Phoenix Core instance over HTTP, registers the
Linux machine as an edge node, sends periodic telemetry/heartbeat updates, and
accepts pushed tasks from Phoenix Core.

## Contents

```text
com/
└── phoenix/
    └── linuxagent/
        ├── LinuxNodeAgent.java
        └── LinuxNodeAgent.class
```

`LinuxNodeAgent.java` is the source implementation.

`LinuxNodeAgent.class` is the compiled class included in this milestone
artifact so the agent can be launched directly without recompiling.

## Requirements

- Linux system
- Java runtime capable of running the compiled agent
- Network connectivity to the Phoenix Core
- Phoenix Core HTTP server running and reachable

The agent reads Linux resource information from:

- `/proc/stat` for CPU utilization
- `/proc/meminfo` for memory information

## Running the agent

From the directory containing the `com/` directory:

```bash
java -cp . com.phoenix.linuxagent.LinuxNodeAgent \
    http://<PHOENIX_CORE_IP>:8080 \
    9090
```

For example:

```bash
java -cp . com.phoenix.linuxagent.LinuxNodeAgent \
    http://10.62.95.63:8080 \
    9090
```

The arguments are:

```text
1. Phoenix Core URL
2. Callback port
```

Both arguments are optional.

Defaults:

```text
Core URL:    http://127.0.0.1:8080
Callback:    9090
```

The callback port must be reachable by Phoenix Core because the Core uses the
agent's callback endpoint to push work.

## HTTP behavior

On startup, the agent:

1. Starts its local HTTP callback server.
2. Registers with Phoenix Core using `POST /register`.
3. Begins sending heartbeats every 5 seconds.
4. Waits for pushed tasks on `POST /execute`.

### Registration

The agent reports information including:

- persistent device ID
- hostname
- hardware class: `LINUX`
- architecture
- CPU core count
- total memory
- adapter type: `linux`
- callback port

### Heartbeat

Every 5 seconds, the agent reports telemetry including:

- CPU utilization
- available RAM
- active task count
- queue length
- measured HTTP round-trip time
- energy-cost estimate

The energy value is an explicit heuristic estimate, not a measurement of
instantaneous physical power consumption.

### Task execution

Phoenix Core pushes a task to:

```text
POST /execute
```

The current agent executes the demonstrated Monte-Carlo workload.

Task parameters include:

```json
{
  "taskId": "...",
  "samples": 2000000
}
```

If `samples` is omitted, the agent defaults to:

```text
2,000,000 samples
```

The response contains:

```json
{
  "status": "SUCCESS",
  "outputData": "Computed Monte-Carlo pi ~= ...",
  "executionTimeMs": 123
}
```

## Persistent device identity

The Linux agent creates and persists its device identity at:

```text
~/.phoenix_linux_device_id
```

This allows the same Linux machine to retain the same Phoenix device ID
across agent restarts.

## Example console output

A normal run will look roughly like:

```text
[LinuxNodeAgent] Registered: ...
[LinuxNodeAgent] Running, listening for pushed tasks on port 9090.
[LinuxNodeAgent] Heartbeat sent: cpu=... freeRam=... activeTasks=... rtt=... energy=...
[LinuxNodeAgent] Received task ..., executing Monte-Carlo (samples=2000000)...
[LinuxNodeAgent] Task ... done: Computed Monte-Carlo pi ~= ... (...ms)
```

## Network considerations

The Core URL is the address from the Linux agent to Phoenix Core.

The callback port is the address Phoenix Core uses to reach the Linux agent.

For a LAN deployment, make sure:

- the Linux machine can reach the Core port (normally `8080`);
- the Core can reach the Linux callback port (normally `9090`);
- firewall rules permit the required traffic;
- the callback address/port advertised to Phoenix is actually reachable.

Do not use `127.0.0.1` for the Core URL when Phoenix Core is running on a
different machine.

## Current milestone scope

This artifact represents the Linux side of the Phoenix HTTP node-agent
experimental milestone.

Current demonstrated workload:

- Monte-Carlo computation

Current transport:

- HTTP

Current limitations:

- no MQTT transport
- no TLS
- local/LAN deployment assumptions
- energy cost is heuristic rather than hardware-measured
- task execution currently targets the demonstrated Monte-Carlo workload

## Stopping the agent

The agent runs continuously until the process is terminated.

Use:

```text
Ctrl+C
```

to stop it.

## Version

Phoenix HTTP Node-Agent:

```text
v0.2
```
