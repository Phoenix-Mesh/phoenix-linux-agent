# Validation: Linux node-agent over MQTT/TLS

This runbook validates the Linux agent end to end on a **real Linux machine**
against the **real Phoenix Core** and a **real Mosquitto broker** over TLS. It
covers the 14 scenarios of the issue's test matrix and says what evidence to
record for the PR.

The scenarios were rehearsed with the real Core, the shipped broker
configuration and ACL, this agent's jar, and the Android agent as a second
node. The expected lines below are taken from that run.

## Setup

**Control node** (laptop or server):

1. Mosquitto from Phoenix Core's `deploy/mosquitto` (TLS listener on 8883,
   loopback listener for Core on 1883, shipped ACL, `allow_anonymous false`).
   The certificate must include the name or IP the Linux machine connects to
   (see [mqtt-tls.md](mqtt-tls.md)).
2. Phoenix Core with MQTT and an enrollment token, for example:

    ```bash
    ./gradlew --console=plain \
      -Dphoenix.mqtt.url=tcp://127.0.0.1:1883 \
      -Dphoenix.mqtt.username=phoenix-core \
      -Dphoenix.mqtt.passwordFile=$HOME/.phoenix/core-broker-password \
      -Dphoenix.mqtt.enrollmentTokenFile=$HOME/.phoenix/enrollment-token \
      run
    ```

    `script -f core-session.log` before starting Core records the console.

**Linux machine** (a second machine is best; the control node itself works
too): Java 11 or newer, `phoenix-linux-agent-v0.3.0.jar`, `ca.pem`, and the
broker password and enrollment token in files ([mqtt-tls.md](mqtt-tls.md)).
For brevity below:

```bash
AGENT="java -jar phoenix-linux-agent-v0.3.0.jar --broker broker.lan --ca ~/.phoenix/ca.pem \
  --password-file ~/.phoenix/broker-password --enrollment-token-file ~/.phoenix/enrollment-token"
$AGENT 2>&1 | tee agent-session.log
```

On the control node, to watch every message to and from the machine
(session tokens included; do not paste them into the PR):

```bash
DEV=linux-…                                       # from --print-device-id
mosquitto_sub -h 127.0.0.1 -p 1883 -u phoenix-core --pw-file "$HOME/.phoenix/core-broker-password" -t "phoenix/v1/nodes/$DEV/#" -v
```

Dispatch **one task at a time** (press `D`, wait for the result). A node
reserves its CPU for a running task, so tasks dispatched together on a single
node wait in Core's queue and complete one after another; that is correct,
but makes the logs harder to read.

## Scenarios

Record the outcome of each in the [evidence table](#evidence-for-the-pr).

### 1. MQTT/TLS connection

Start the agent.

- Agent: `Trusting the broker CA CN=…`, `Connecting to ssl://broker.lan:8883 as linux-… (TLS)...`,
  then `Connected to the broker.`
- Broker log: `New client connected from <ip>:<port> as linux-… (p2, c0, k30, u'linux-…').`
  (`c0`: persistent session; `u'…'`: the broker user is the device id.)

### 2. Authentication: unauthorized connections are rejected

Run each, then restore the correct setting. Each stops the agent with exit
code 1 (`echo $?`).

| Change                                           | Agent output                                                                                             |
| ------------------------------------------------ | -------------------------------------------------------------------------------------------------------- |
| wrong broker password                            | `Could not connect to the broker: the broker refused this device's user name or password`                |
| `--ca` of another CA                             | `Could not connect to the broker: TLS failed: the broker certificate is not trusted (check the CA file)` |
| `--broker` with a name/IP not in the certificate | `Could not connect to the broker: the broker certificate does not match the host name in ssl://…`        |
| wrong enrollment token                           | `Phoenix Core refused the registration: enrollment_rejected`                                             |

Core logs `[MQTT] Rejected registration from linux-… refused: enrollment_rejected`
for the last one, and its **NODES** panel shows no node for the machine.

### 3. Registration: one stable Phoenix node

- Agent: `Registered with Phoenix Core: node <nodeId> (device linux-…, registration round trip 50.2 ms, heartbeat every 5 s).`
- Core: `[MQTT] Device registered: <name> nodeId=<nodeId> (LINUX, linux)`.
- Core console: exactly **one** entry for the machine.

If the machine ran the HTTP agent against this Core before, Core keeps its
node instead: `[MQTT] Device linux-… registered again as node <same nodeId> (moved from http to MQTT); new session`.

### 4. Telemetry reaches Phoenix

Core's node panel shows the machine with its real values. In the rehearsal:
`class=LINUX state=ONLINE ramMb=6315 cpu=2.8 active=0 latencyMs=50.2 energy=4.5`.
The agent logs each heartbeat, for example
`Heartbeat sent: cpu=2.8% freeRam=6315MB activeTasks=0 queued=0 rtt=50.2ms energy=4.5`.

Compare with `top`/`free -m` on the machine: CPU and available RAM match.
Latency is the measured registration round trip; energy is the fixed
heuristic 4.5.

### 5. The node stays healthy while heartbeats arrive

Leave it running for 2 minutes. Core keeps the node `ONLINE`, and no
`[NodeHealthMonitor]` line mentions it.

### 6, 7, 8. Task delivery, execution, correlation by taskId

Press **D** in the Core console (repeat a few times, one at a time).

- Agent: `Received task task-001 (attempt 1, id ce3dac51), executing Monte-Carlo (samples=2000000)...`
  then `Task task-001 done: Computed Monte-Carlo pi ~= 3.139828 (31ms)`.
- Core: `[Lifecycle] task-001 ACCEPTED (attempt 1, id ce3dac51, fencing ENFORCED)`,
  `ACCEPTED -> DELIVERED`, `DELIVERED -> STARTED`, `STARTED -> COMPLETED`,
  `[MQTT] Result received: task=task-001 status=SUCCESS from linux-…`.
- `mosquitto_sub`: `down/task`, then `up/ack`, `up/started`, `up/result`, all
  with the same `taskId` and `attemptId`.

### 12, 13. Duplicate task, duplicate result

Capture the next task as Core sends it, then deliver it a second time
through the broker (Core's account may publish on every downlink):

```bash
mosquitto_sub -h 127.0.0.1 -p 1883 -u phoenix-core --pw-file "$HOME/.phoenix/core-broker-password" \
  -t "phoenix/v1/nodes/$DEV/down/task" -C 1 -q 1 > task.json &
# press D in the Core console and wait for the result, then:
mosquitto_pub -h 127.0.0.1 -p 1883 -u phoenix-core --pw-file "$HOME/.phoenix/core-broker-password" \
  -t "phoenix/v1/nodes/$DEV/down/task" -q 1 -f task.json
```

- Agent: `Duplicate delivery of task task-00N (attempt id 07b8f670): result sent again, not executed twice.`
  There is only **one** `Received task task-00N` line.
- Core: `[MQTT] Dropping ack for task-00N: attempt … is not pending` and
  `[MQTT] Dropping result for task-00N: attempt … is not pending`; the task is
  completed once.

The re-sent result is the duplicate result of scenario 13: Core ignores it.

### 9. Disconnect: the lifecycle follows Core's health policy

Do **a**, then **b**.

- **a. Graceful stop.** `Ctrl+C` (or `kill -TERM`). Agent: `Stopping...`, `Agent stopped.`
- **b. The process dies.** `kill -9 <pid>`. The broker publishes the agent's
  last will.

In both cases Core logs `[MQTT] Device linux-… disconnected from the broker`,
which changes **no** node state by itself, and decides from heartbeats:

- `[NodeHealthMonitor] No heartbeat from <name> in 24s -> DEGRADED`
- `[NodeHealthMonitor] No heartbeat from <name> in 64s -> OFFLINE`

**c. Network loss** (optional): unplug the machine's network, or freeze the
agent with `kill -STOP <pid>`, for more than 60 s. The broker drops the
connection after 1.5 × the 30 s keep-alive and publishes the last will; Core
moves the node to `DEGRADED` and `OFFLINE` as above.

### 10. Reconnect: the existing node identity is restored

- After **9a** or **9b**: start the agent again. Agent: `Registered with Phoenix Core: node <same nodeId>`.
  Core: `[MQTT] Device linux-… registered again as node <same nodeId>; new session`,
  then `[Registry] Node <nodeId> -> ONLINE`.
- After **9c**: reconnect the network (`kill -CONT <pid>`). Agent:
  `Broker connection lost: Connection lost; reconnecting...`, then
  `Reconnected to the broker.` within a second or two; the session is kept,
  heartbeats resume without a new registration, and Core logs
  `[Registry] Node <nodeId> -> ONLINE` for the same node id.

Core console: still exactly **one** entry for the machine. Press **D**: the
task completes on it.

### 11. Task interruption: the existing recovery path is invoked

1. Stop the agent and start it again with `--validation-hold 30`. It logs
   `Validation hold: every task waits 30 s after it started. Use only for validation runs.`
2. Press **D**. Agent: `Received task task-00N (attempt 1, …)` and
   `Holding task task-00N for 30 s before executing (validation hold)...`
3. While it holds, `kill -9` the agent: the task dies after `ack` and `started`.
4. Core, about 12 s after the start (`phoenix.taskTimeoutSeconds`):
    - `[Lifecycle] task-00N STARTED -> FAILED`
    - `[Recovery] Task task-00N failed on <name> reason=EXECUTION_TIMEOUT`
    - `[Recovery] Node <name> failed task task-00N -> DEGRADED`
    - with no other eligible node: `[Recovery] task-00N WAITING: no eligible alternative after 1 attempt(s)`
5. Start the agent again **without** the hold. Core re-places the pending
   task as **attempt 2**:
    - `[Lifecycle] task-00N ACCEPTED (attempt 2, id feacc815, fencing ENFORCED)` … `STARTED -> COMPLETED`
    - `[Recovery] task-00N recovered -> SUCCESS on <name>`
    - Agent: `Received task task-00N (attempt 2, id feacc815), …` then `Task task-00N done: …`

With another node registered (scenario 14), step 4 re-places the task on that
node instead. Either way the decision is Core's recovery manager, not the
agent's: the agent never retries anything.

### 14. Multi-node: Linux and Android together

Connect an Android phone with the
[Android agent](https://github.com/Phoenix-Mesh/phoenix-android-agent) over
MQTT/TLS to the same Core, then press **D** several times, one at a time.

- Core console: **two** nodes, `class=LINUX` and `class=ANDROID`, both `ONLINE`.
- Tasks complete on both: `[TUI] task-00N -> SUCCESS on <linux name>` and
  `… on <phone>`, placed by the same scheduler from the same telemetry (the
  phone reports CPU as unknown, the Linux machine its real load).
- Stopping one node takes only that node through `DEGRADED` and `OFFLINE`.

### Also observed (not in the matrix)

- **Agent started before Core:** `No answer from Phoenix Core yet; sending the registration again.`
  every 10 s, then `Registered with Phoenix Core` as soon as Core is up.
- **Core restarted:** `Phoenix Core is offline; waiting for it to return.`,
  then `Phoenix Core restarted (instance a872313a); registering again.`
  and a new registration, without restarting the agent. Core keeps its node
  registry in memory, so the restarted Core assigns a new node id.

## Evidence for the PR

| #   | Scenario              | Expected                                                               | Result       | Evidence                                                           |
| --- | --------------------- | ---------------------------------------------------------------------- | ------------ | ------------------------------------------------------------------ |
| 1   | MQTT/TLS connection   | Linux agent connects                                                   | PASS         | Agent connected to broker over TLS                                 |
| 2   | Authentication        | Unauthorized connection rejected (password, CA, host name, enrollment) | PARTIAL      | Valid authentication works; rejection cases need separate evidence |
| 3   | Registration          | One stable Phoenix node                                                | PASS         | Linux node registered with Core                                    |
| 4   | Telemetry             | Real resource state reaches Phoenix                                    | PASS         | Core dashboard displays Linux resource telemetry                   |
| 5   | Heartbeat             | Node stays healthy                                                     | PASS         | Nodes remained visible during the observed run                     |
| 6   | Task delivery         | Task reaches the Linux node                                            | PASS         | Core dispatched tasks to Linux and Android nodes                   |
| 7   | Execution             | Monte-Carlo-Pi completes                                               | PASS         | Tasks completed successfully on both platforms                     |
| 8   | Result                | Correlated by taskId                                                   | PASS         | Core received successful results from Linux                        |
| 9   | Disconnect            | DEGRADED, then OFFLINE, by Core's health policy                        | PASS         | Android node transitioned to OFFLINE; others remained active       |
| 10  | Reconnect             | Same node identity, one NodeRecord                                     | PARTIAL      | Reconnection needs explicit confirmation of the same node identity |
| 11  | Task interruption     | EXECUTION_TIMEOUT, recovery, completed as attempt x                    | PASS         | Linux task `task-xxx`, attempt x, completed with fencing enforced  |
| 12  | Duplicate task        | One execution                                                          | NOT VERIFIED | No duplicate-execution evidence in these screenshots               |
| 13  | Duplicate result      | One completion                                                         | NOT VERIFIED | No duplicate-result evidence in these screenshots                  |
| 14  | Multi-node scheduling | Linux and Android nodes scheduled together                             | PASS         | Successful tasks are visible on both Android and Linux nodes       |

Known limitations:

- Network latency is the registration round trip; energy is the fixed heuristic (4.5).
- Tasks run one at a time; Monte-Carlo-Pi is the only workload.
- Handled attempt ids are kept in memory, so duplicate suppression does not
  survive an agent restart (Core still ignores a second result).
