package arqhmac

import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.nodeinfo.NodeInfoParam

/**
 * MAC agent implementing two protocols, selected by the `proposed` switch.
 *
 * proposed = false : ARQ_HMAC as published by Dao & Tran (IEEE Access 2025). The one-way propagation
 *                    delay tDi to the master is measured once in the INIT phase and used for the whole
 *                    run; guard time and TDMA slots are fixed (Eq. 1-10 of the paper).
 *
 * proposed = true  : Adaptive ARQ_HMAC. Same three phases, but the protocol adapts to the channel:
 *   1. Adaptive delay estimate (progress report, Sec. 3): EWMA of delay samples (Report Eq. 1), and
 *      the schedule is recomputed when the estimate drifts more than theta (Report Eq. 3, Algorithm 1).
 *   2. Timestamped delay samples: the master writes its transmit time into every ACK/NACK, so every
 *      master frame a sensor hears gives a one-way sample  tObs = t_rx_end - t_tx - tACK.
 *   3. Adaptive guard time (RFC 6298): dev = (1-beta) dev + beta |tObs - tHat|,
 *      tguard = td + tACK + max(tACK, 2 K dev); starts equal to the paper's 2 td + tACK.
 *   4. Arrival-aligned TDMA slots: sensors transmit tDi before their slot so frames arrive back to
 *      back at the master; a slot needs tguard instead of tmax + tguard (Eq. 6).
 *   5. Adaptive backoff: random wait in [0, cw * 2 tmax] before a contended transmission; cw doubles on
 *      every NACK/timeout (up to 16) and resets on success (binary exponential backoff, IEEE 802.11).
 *
 * Phases (paper Sec. III):
 *   INIT : each sensor sends one probe in its own interval; the master ACKs it (Eq. 1, 2).
 *   CSMA : sense the channel, transmit, wait ttime_out for ACK/NACK (Eq. 3, 4).
 *   TDMA : after a NACK or timeout, every sensor retransmits in its own slot, without ACK (Eq. 5-10).
 *
 * Addressing: master = masterAddr, sensors = masterAddr+1 .. masterAddr+numSensors.
 */
class ArqHmacAgent extends UnetAgent {

  // ---------------------------------------------------------------- Constants
  static final int INIT = 0, CSMA = 1, TDMA = 2                              // phases
  static final int T_INIT = 1, T_DATA = 2, T_ACK = 3, T_NACK = 4, T_TDMA_DATA = 5   // frame types
  static final double BETA = 0.25, K = 4            // RFC 6298 constants (adaptive guard)
  static final int CW_MIN = 1, CW_MAX = 16          // contention window in units of 2 tmax (802.11 BEB)

  // ---------------------------------------------------------------- Configuration
  boolean proposed      = false     // false = ARQ_HMAC (2025 paper), true = Adaptive ARQ_HMAC
  int     masterAddr    = 1
  int     myAddr        = 0         // 0 = read from the node
  int     numSensors    = 10
  double  dMax          = 400.0     // maximum master-sensor distance (m)
  double  soundSpeed    = 1500.0    // m/s
  List<Double> arrivals = []        // packet generation times (s), from the shared traffic trace
  long    rngSeed       = 1         // seed of this node's backoff random numbers (proposed)
  double  senseInterval = 0.01      // re-check period while the channel is busy (s)
  double  alpha         = 0.7       // EWMA smoothing factor (Report Eq. 1)
  double  theta         = 0.005     // change-detection threshold, s (Report Eq. 3)
  List masterLocation   = [0.0, 0.0, -50.0]   // only for the estimate-error diagnostic

  // ---------------------------------------------------------------- Protocol timing (paper Table 1)
  double td, tAck                   // DATA and ACK/NACK frame durations, read from the modem
  double tMax, tGuard, tSlot, tInitial
  double tDi                        // delay measured in the INIT phase (Eq. 2)
  double tDiHat                     // EWMA estimate (proposed)
  double tDiSched                   // delay value used for timeout and TDMA schedule
  double tDiDev                     // mean deviation of the delay samples (proposed)
  double tTimeout                   // ttime_out_Nodei (Eq. 3)
  double tStartTDMA, tSlotI, tEndTDMA

  // ---------------------------------------------------------------- State
  boolean isMaster = false
  int     phase    = INIT
  int     slotIdx  = 0
  AgentID phy
  Random  rng                       // own generator: results do not depend on other simulations

  List<Map> queue      = []         // [seq, gen, tries]
  int     nextSeq      = 0
  boolean waitingAck   = false
  boolean sensing      = false
  boolean deferred     = false      // proposed: back off before the next transmission
  int     cw           = CW_MIN     // proposed: contention window
  double  probeTime    = 0          // ti0 (Eq. 2)
  int     lastTxSeq    = -1
  WakerBehavior ackTimer
  double  masterQuietUntil = -1     // master: no new NACK until its TDMA round is over
  double  masterTxFreeAt   = 0      // master: when its modem is free (queued frames go back to back)
  Map<Integer, Double> sensorDev = [:]   // master (proposed): last dev reported by each sensor

  // ---------------------------------------------------------------- Metrics
  // sensors
  int pktGenerated = 0, txCsma = 0, txTdma = 0, txRetransmit = 0
  int ackOk = 0, ackTimeouts = 0, nackRx = 0, delaySamples = 0, schedUpdates = 0
  List<List<Double>> estTrace = []  // [time, true delay, delay in use], every 10 s
  // master
  Map<String, Double> delivered = [:]    // "src:seq" -> end-to-end delay (s)
  int duplicates = 0, collisions = 0, collisionsInTdma = 0, nackTx = 0

  // ================================================================ Startup
  @Override
  void setup() {
    register Services.MAC
  }

  @Override
  void startup() {
    phy = agentForService(Services.PHYSICAL)
    rng = new Random(rngSeed)
    subscribe topic(phy)                              // BadFrameNtf, frames for other nodes
    subscribe topic(org.arl.unet.Topics.DATAGRAM)     // frames for this node and broadcasts

    if (myAddr == 0) myAddr = (int) get(agentForService(Services.NODE_INFO), NodeInfoParam.address)
    isMaster = myAddr == masterAddr
    slotIdx  = myAddr - masterAddr - 1

    td   = (double) get(phy, Physical.DATA, PhysicalChannelParam.frameDuration)
    tAck = (double) get(phy, Physical.CONTROL, PhysicalChannelParam.frameDuration)

    tMax     = dMax / soundSpeed
    tDiDev   = td / (2 * K)                           // makes the adaptive guard start at Eq. (4)
    tGuard   = 2 * td + tAck                          // Eq. (4)
    tSlot    = slotWidth(tGuard)                      // Eq. (6)
    tInitial = numSensors * (2 * tMax + td + tAck)    // Eq. (1)
    useDelay(tMax)                                    // fallback if the INIT probe gets no ACK

    if (!isMaster) {
      // INIT phase: sensor i probes in interval i (Fig. 2)
      add new WakerBehavior(ms(slotIdx * (2 * tMax + td + tAck)), {
        probeTime = now()
        send(T_INIT, masterAddr, Physical.DATA, 0, 0)
      })
    }

    add new WakerBehavior(ms(tInitial), {             // t = 0 of the operating phases
      phase = CSMA
      if (!isMaster) {
        arrivals.each { double t -> add new WakerBehavior(ms(t - now()), { generate(t) }) }
        add new TickerBehavior(10000, { recordEstimate() })
      }
    })
  }

  // ================================================================ Sensor: CSMA phase
  void generate(double t) {
    queue << [seq: nextSeq++ & 0xFFFF, gen: t, tries: 0]
    pktGenerated++
    trySend()
  }

  void trySend() {
    if (phase != CSMA || waitingAck || sensing || queue.isEmpty()) return
    if (get(phy, PhysicalParam.busy)) {               // carrier detected: wait until the channel is free
      sensing = true
      deferred = proposed
      add new WakerBehavior(ms(senseInterval), { sensing = false; trySend() })
      return
    }
    if (deferred) {                                   // proposed: random backoff before transmitting
      deferred = false
      sensing = true
      double wait = rng.nextDouble() * cw * 2 * tMax
      add new WakerBehavior(ms(wait), { sensing = false; trySend() })
      return
    }
    Map pkt = queue[0]
    pkt.tries++
    txCsma++
    waitingAck = true
    lastTxSeq  = pkt.seq
    send(T_DATA, masterAddr, Physical.DATA, pkt.seq, pkt.gen, devReport())
    ackTimer = new WakerBehavior(ms(tTimeout), {      // Eq. (3)
      ackTimer = null
      if (waitingAck) {
        waitingAck = false
        ackTimeouts++
        widenWindow()
        enterTdma(now())
      }
    })
    add ackTimer
  }

  void onAck(int seq) {
    if (phase == INIT) {
      useDelay(Math.max(0.001, (now() - (td + tAck) - probeTime) / 2))   // Eq. (2)
      return
    }
    if (seq != lastTxSeq || !waitingAck) return
    ackTimer?.stop()
    ackTimer = null
    waitingAck = false
    ackOk++
    queue.remove(0)
    cw = CW_MIN
    trySend()
  }

  void widenWindow() {
    if (proposed) cw = Math.min(2 * cw, CW_MAX)
  }

  // ================================================================ Proposed: adaptive delay estimate
  void delaySample(double tObs) {
    if (tObs < 0 || tObs > 2 * tMax) return                     // not a plausible sample
    delaySamples++
    tDiDev = (1 - BETA) * tDiDev + BETA * Math.abs(tObs - tDiHat)   // RFC 6298: deviation first
    tGuard = guardFor(tDiDev)
    tTimeout = 2 * tDiSched + tGuard
    tDiHat = alpha * tObs + (1 - alpha) * tDiHat                // Report Eq. (1)
    if (Math.abs(tDiHat - tDiSched) > theta) {                  // Report Eq. (3)
      tDiSched = tDiHat                                         // Algorithm 1, line 8
      tTimeout = 2 * tDiSched + tGuard                          // line 9 (TDMA times use tDiSched)
      schedUpdates++
    }
  }

  void useDelay(double d) {
    tDi = tDiHat = tDiSched = d
    tTimeout = 2 * tDiSched + tGuard                            // Eq. (3)
  }

  // td + tACK cover the DATA and ACK frames, the rest covers delay uncertainty.
  // At dev = td / (2K) this equals the paper's 2 td + tACK; it never drops below td + 2 tACK.
  double guardFor(double dev) {
    td + tAck + Math.max(tAck, 2 * K * dev)
  }

  // TDMA slot width: Eq. (6), or the guard alone with arrival-aligned slots (proposed)
  double slotWidth(double guard) {
    proposed ? guard : tMax + guard
  }

  // dev in ms, reported to the master in every DATA frame (proposed; capped at one byte)
  int devReport() {
    proposed ? (int) Math.min(255, Math.round(tDiDev * 1000)) : 0
  }

  // ================================================================ Sensor: TDMA phase
  void enterTdma(double tAcki) {
    if (phase == TDMA) return
    phase = TDMA
    ackTimer?.stop()
    ackTimer = null
    waitingAck = false

    tStartTDMA = tAcki + tMax - tDiSched                        // Eq. (5)
    // Eq. (7)-(8): one slot per sensor. The paper's ni = floor(tDi / tslot) is always 0 because
    // tDi < tmax < tslot, so the slot index is the sensor's position in the network.
    if (proposed) {
      // slot i is the interval in which the frame ARRIVES at the master: transmit tDi earlier
      double firstArrival = tStartTDMA + tMax
      tSlotI   = firstArrival + slotIdx * tSlot - tDiSched
      tEndTDMA = firstArrival + numSensors * tSlot
    } else {
      tSlotI   = tStartTDMA + slotIdx * tSlot
      tEndTDMA = tStartTDMA + numSensors * tSlot                // Eq. (9)-(10)
    }

    add new WakerBehavior(ms(tSlotI - now()), {
      if (phase == TDMA && !queue.isEmpty()) {
        Map pkt = queue.remove(0)                               // no ACK in TDMA (paper Sec. III-B.1)
        txTdma++
        if (pkt.tries > 0) txRetransmit++
        send(T_TDMA_DATA, masterAddr, Physical.DATA, pkt.seq, pkt.gen, devReport())
      }
    })
    add new WakerBehavior(ms(tEndTDMA - now()), {
      phase = CSMA
      deferred = proposed                             // all sensors leave TDMA together: back off first
      trySend()
    })
  }

  // ================================================================ Master
  void masterRx(int type, int src, int seq, double gen, int dev) {
    if (proposed && (type == T_DATA || type == T_TDMA_DATA)) sensorDev[src] = dev / 1000.0
    // ACKs are broadcast with the destination in the last payload byte, so that every sensor
    // overhears them (UnetStack does not pass control frames addressed to other nodes up the stack)
    if (type == T_INIT) {
      send(T_ACK, Address.BROADCAST, Physical.CONTROL, 0, 0, src)
    } else if (type == T_DATA || type == T_TDMA_DATA) {
      if (type == T_DATA) send(T_ACK, Address.BROADCAST, Physical.CONTROL, seq, 0, src)
      String key = "$src:$seq"
      if (delivered.containsKey(key)) duplicates++
      else delivered[key] = now() - gen
    }
  }

  void masterCollision() {
    collisions++
    if (now() < masterQuietUntil) {
      collisionsInTdma++
      return
    }
    nackTx++
    if (proposed) {
      // one slot width for everybody, sized for the least predictable sensor
      // (sensors not heard from yet count with the paper's guard)
      double worst = (0..<numSensors).collect { i -> sensorDev.getOrDefault(masterAddr + 1 + i, td / (2 * K)) }.max()
      tGuard = guardFor(worst)
      tSlot  = slotWidth(tGuard)
    }
    send(T_NACK, Address.BROADCAST, Physical.CONTROL, (int) Math.round(tSlot * 1000), 0)   // slot width in ms
    masterQuietUntil = now() + tAck + 2 * tMax + numSensors * tSlot   // last slot has arrived
  }

  // ================================================================ Frames
  // Payload: [type, seq (2 bytes), time in ms (4 bytes), last byte].
  // time: generation time in DATA frames, transmit time in master frames.
  // last byte: dev in ms in DATA frames, destination address in ACK frames.
  void send(int type, int to, int frameType, int seq, double gen, int dev = 0) {
    if (isMaster) {
      double start = Math.max(now(), masterTxFreeAt)
      masterTxFreeAt = start + tAck
      gen = start
    }
    long g = Math.round(gen * 1000)
    byte[] data = [type, (seq >> 8) & 0xFF, seq & 0xFF,
                   (g >> 24) & 0xFF, (g >> 16) & 0xFF, (g >> 8) & 0xFF, g & 0xFF, dev & 0xFF] as byte[]
    phy << new TxFrameReq(to: to, type: frameType, data: data)
  }

  @Override
  void processMessage(Message msg) {
    if (msg instanceof RxFrameNtf) {
      byte[] d = msg.data
      if (d == null || d.length < 8) return
      long g = ((d[3] & 0xFFL) << 24) | ((d[4] & 0xFF) << 16) | ((d[5] & 0xFF) << 8) | (d[6] & 0xFF)
      if (proposed && !isMaster && phase != INIT && msg.from == masterAddr) {
        delaySample(now() - g / 1000.0 - tAck)                  // one-way delay from any master frame
      }
      if (msg.to != myAddr && msg.to != Address.BROADCAST) return
      int type = d[0] & 0xFF
      int seq  = ((d[1] & 0xFF) << 8) | (d[2] & 0xFF)
      if (isMaster) masterRx(type, msg.from, seq, g / 1000.0, d[7] & 0xFF)
      else if (type == T_ACK && (d[7] & 0xFF) == myAddr) onAck(seq)
      else if (type == T_NACK && phase == CSMA) {
        nackRx++
        if (waitingAck) widenWindow()                           // our frame collided
        if (proposed && seq > 0) tSlot = seq / 1000.0           // slot width announced by the master
        enterTdma(now())
      }
    } else if (msg instanceof BadFrameNtf && isMaster && phase != INIT) {
      masterCollision()
    }
  }

  // ================================================================ Diagnostics
  // Compares the delay value in use with the true delay from the node's real position
  void recordEstimate() {
    def p = get(agentForService(Services.NODE_INFO), NodeInfoParam.location)
    if (p == null) return
    double dx = p[0] - masterLocation[0], dy = p[1] - masterLocation[1], dz = p[2] - masterLocation[2]
    estTrace << [now(), Math.sqrt(dx * dx + dy * dy + dz * dz) / soundSpeed, tDiSched]
  }

  double meanEstimateError() {
    estTrace ? estTrace.sum { Math.abs(it[2] - it[1]) } / estTrace.size() : 0.0
  }

  double now() { currentTimeMillis() / 1000.0 }

  static long ms(double s) { Math.max(1L, Math.round(s * 1000)) }
}
