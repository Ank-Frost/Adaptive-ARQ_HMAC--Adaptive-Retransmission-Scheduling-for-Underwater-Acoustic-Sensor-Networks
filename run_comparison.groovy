// ARQ_HMAC (Dao & Tran, 2025) vs Adaptive ARQ_HMAC (proposed) - full comparison
//
// Compares PDR, throughput, retransmissions, collision rate and end-to-end delay of both protocols
// under varying traffic loads and changing channel conditions. For every (condition, load, seed) one
// traffic trace is generated and replayed through BOTH protocols with the same topology and node
// motion; the only difference between the two runs is ArqHmacAgent.proposed = false / true.
//
// Usually started through run_all.sh. Directly, from this folder:
//     unet-7.0.0/bin/unet run_comparison.groovy                    # everything, one process
//     ONLY_CONDITION=Mobile-400m ONLY_SEEDS=1-5 unet-7.0.0/bin/unet run_comparison.groovy   # a part
//
// Output:
//   results/runs_<tag>.csv          one row per run (one file per process)
//   results/estimate_trace.csv      delay estimate vs true delay over time for one run (TRACE_RUN)
// The tables printed on screen are for a quick look; plot_results.py computes the final statistics.

import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.sim.*
import org.arl.unet.sim.channels.*
import arqhmac.ArqHmacAgent
import arqhmac.Scenario

// ------------------------------------------------------------------ Experiment configuration
int  SIM_DURATION = 1800                       // seconds per run
def  LOADS        = [0.05, 0.1, 0.2, 0.3]      // offered load G (packets per td, whole network)
def  SEEDS        = (1..10).toList()           // independent traffic traces per (condition, load)
def  CONDITIONS   = [
  // The paper's setup: nothing moves, the propagation delay never changes.
  [name: 'Static-400m', range: 400.0,  auvSpeed: 0.0],
  // AUVs at 0.3 m/s in the paper's 400 m network: one-way delay changes by up to 0.17 s.
  [name: 'Mobile-400m', range: 400.0,  auvSpeed: 0.3],
  // Long-range network, AUVs at 2 m/s: one-way delay changes by up to 1.3 s.
  [name: 'Mobile-3km',  range: 3000.0, auvSpeed: 2.0],
]
def PROTOCOLS = [
  [name: 'ARQ_HMAC', proposed: false],         // Dao & Tran, IEEE Access 2025
  [name: 'Proposed', proposed: true],          // Adaptive ARQ_HMAC
]
double ALPHA = 0.7                             // EWMA smoothing factor (Report Eq. 1)
double THETA = 0.005                           // change-detection threshold, s (Report Eq. 3)
def TRACE_RUN = [condition: 'Mobile-400m', load: 0.1, seed: 1]   // run exported to estimate_trace.csv

// Optional split into parallel processes (see run_all.sh)
String ONLY_CONDITION = System.getenv('ONLY_CONDITION')
if (ONLY_CONDITION) CONDITIONS = CONDITIONS.findAll { it.name == ONLY_CONDITION }
assert CONDITIONS : "unknown condition ${ONLY_CONDITION}"
String ONLY_SEEDS = System.getenv('ONLY_SEEDS')                  // e.g. "1-5"
if (ONLY_SEEDS) {
  def (lo, hi) = ONLY_SEEDS.split('-')*.toInteger()
  SEEDS = SEEDS.findAll { it >= lo && it <= hi }
}

// ------------------------------------------------------------------ Channel and modem (paper Table 2)
// 1200 bps; DATA = 1200 bits (td = 1 s), CONTROL = 120 bits (tAck = 0.1 s); no extra overheads.
// ProtocolChannelModel: a frame is lost only by collision (or because the receiver is transmitting).
channel.model = ProtocolChannelModel
channel.soundSpeed = Scenario.SOUND_SPEED.mps
channel.pDetection = 1
channel.pDecoding = 1
modem.dataRate = [1200, 1200].bps
modem.frameLength = [15, 150].bytes
modem.headerLength = 0
modem.preambleDuration = 0
modem.txDelay = 0

// ------------------------------------------------------------------ One simulation run
def mean = { List xs -> xs ? xs.sum() / xs.size() : 0.0 }

def runOnce = { Map cond, Map proto, Map trace, long seed, double load ->
  channel.communicationRange = cond.range.m
  channel.detectionRange = cond.range.m
  channel.interferenceRange = cond.range.m

  def master = null
  def sensorAgents = []
  simulate SIM_DURATION.seconds, {
    master = new ArqHmacAgent(proposed: proto.proposed, numSensors: Scenario.NUM_SENSORS, dMax: cond.range,
      alpha: ALPHA, theta: THETA)
    node 'Master', address: 1, location: [0.m, 0.m, Scenario.DEPTH.m], stack: { c -> c.add 'mac', master }

    Scenario.sensors(cond.range).each { s ->
      def mac = new ArqHmacAgent(proposed: proto.proposed, numSensors: Scenario.NUM_SENSORS, dMax: cond.range,
        alpha: ALPHA, theta: THETA, arrivals: trace[s.addr], rngSeed: Scenario.nodeSeed(seed, load, s.addr))
      sensorAgents << [info: s, agent: mac]
      boolean mobile = s.kind == 'AUV' && cond.auvSpeed > 0
      def n = node "Sensor-${s.addr}", address: s.addr, location: [s.x.m, s.y.m, s.z.m],
        mobility: mobile, stack: { c -> c.add 'mac', mac }
      if (mobile) n.motionModel = Scenario.patrolLegs(s, cond.range, cond.auvSpeed, SIM_DURATION)
        .collect { [time: it.time.seconds, speed: it.speed.mps, yaw: it.yaw.deg] }
    }
  }

  def sens   = sensorAgents*.agent
  int gen    = sens.sum { it.pktGenerated }
  int frames = sens.sum { it.txCsma + it.txTdma }
  int ok     = master.delivered.size()
  [
    generated: gen, delivered: ok,
    pdr:        gen ? 100.0 * ok / gen : 0.0,                                   // %
    throughput: ok * master.td / (SIM_DURATION - master.tInitial),              // normalized S
    retx:       sens.sum { it.txRetransmit },
    collRate:   frames ? 100.0 * master.collisions / frames : 0.0,             // %
    delay:      ok ? master.delivered.values().sum() / ok : 0.0,               // s
    timeouts:   sens.sum { it.ackTimeouts },
    duplicates: master.duplicates,
    estErr:     1000.0 * mean(sens.collect { it.meanEstimateError() }),        // ms
    guard:      mean(sens*.tGuard),                                             // s, sensors' final guard
    slot:       master.tSlot,                                                   // s, final TDMA slot width
    collisions: master.collisions, frames: frames,
    sensors: sensorAgents,
  ]
}

// ------------------------------------------------------------------ Run all experiments
def say = { String line = '' -> println line }

def results = []
int total = CONDITIONS.size() * LOADS.size() * SEEDS.size() * PROTOCOLS.size(), done = 0
def traceRows = []

say "=" * 100
say " ARQ_HMAC vs Adaptive ARQ_HMAC  -  identical traffic and node motion for both protocols"
say "=" * 100
say String.format(" %d sensors (4 moored, 6 AUV), %d s per run, seeds %s, alpha = %.2f, theta = %.3f s",
  Scenario.NUM_SENSORS, SIM_DURATION, SEEDS, ALPHA, THETA)
say " Loads G: ${LOADS}"
say " Conditions: " + CONDITIONS.collect { "${it.name} (range ${it.range as int} m, AUV ${it.auvSpeed} m/s)" }.join(', ')
println " Running ${total} simulations..."

CONDITIONS.each { cond ->
  LOADS.each { load ->
    SEEDS.each { seed ->
      def trace = Scenario.trace(seed, load, cond.range, SIM_DURATION)   // shared by both protocols
      def pair = PROTOCOLS.collect { proto ->
        def r = runOnce(cond, proto, trace, seed, load)
        r.putAll(condition: cond.name, load: load, seed: seed, protocol: proto.name)
        if (cond.name == TRACE_RUN.condition && load == TRACE_RUN.load && seed == TRACE_RUN.seed) {
          r.sensors.findAll { it.info.kind == 'AUV' }.each { s ->
            s.agent.estTrace.each { e -> traceRows << [cond.name, proto.name, s.info.addr] + e }
          }
        }
        r.remove('sensors')
        results << r
        done++
        r
      }
      assert pair*.generated.unique().size() == 1 : 'both protocols must get the same traffic'
    }
    println String.format("   %-12s G=%.2f done (%d/%d)", cond.name, load, done, total)
  }
}

// ------------------------------------------------------------------ Tables per condition
def pick = { String c, double g, String p -> results.findAll { it.condition == c && it.load == g && it.protocol == p } }

CONDITIONS.each { cond ->
  say ''
  say "=" * 100
  say String.format(" %s  (range %d m, AUV speed %.1f m/s)  -  mean over %d seeds",
    cond.name, cond.range as int, cond.auvSpeed, SEEDS.size())
  say "=" * 100
  say String.format(" %5s  %-10s| %5s %8s %7s %7s %7s %8s | %6s %5s %8s",
    'G', 'Protocol', 'Gen', 'PDR(%)', 'S', 'ReTx', 'Coll(%)', 'Delay(s)', 'TOuts', 'Dup', 'EstErr')
  say " " + "-" * 98
  LOADS.each { g ->
    PROTOCOLS.each { p ->
      def rs = pick(cond.name, g, p.name)
      say String.format(" %5.2f  %-10s| %5.0f %8.2f %7.3f %7.1f %7.2f %8.2f | %6.1f %5.1f %6.1fms",
        g, p.name, mean(rs*.generated), mean(rs*.pdr), mean(rs*.throughput), mean(rs*.retx),
        mean(rs*.collRate), mean(rs*.delay), mean(rs*.timeouts), mean(rs*.duplicates), mean(rs*.estErr))
    }
    say " " + "-" * 98
  }
}

// ------------------------------------------------------------------ Summary
// Change = Proposed relative to ARQ_HMAC (pooled over loads and seeds).
// b/e/w  = paired comparison per (load, seed): same traffic, so each pair is a fair test.
def METRICS = [
  [label: 'PDR (%)',               f: { it.pdr },        higherIsBetter: true],
  [label: 'Throughput S',          f: { it.throughput }, higherIsBetter: true],
  [label: 'Retransmissions',       f: { it.retx },       higherIsBetter: false],
  [label: 'Collision rate (%)',    f: { it.collRate },   higherIsBetter: false],
  [label: 'End-to-end delay (s)',  f: { it.delay },      higherIsBetter: false],
  [label: 'ACK timeouts',          f: { it.timeouts },   higherIsBetter: false],
  [label: 'Delay est. error (ms)', f: { it.estErr },     higherIsBetter: false],
]

say ''
say "=" * 100
say " SUMMARY: Proposed vs ARQ_HMAC (pooled over all loads and seeds)"
say " b/e/w = paired runs where Proposed is better / equal / worse than ARQ_HMAC on the same traffic"
say "=" * 100
say String.format(" %-12s %-22s %10s %10s %9s %10s", 'Condition', 'Metric', 'ARQ_HMAC', 'Proposed', 'Change', 'b/e/w')
CONDITIONS.each { cond ->
  say " " + "-" * 78
  def base = results.findAll { it.condition == cond.name && it.protocol == 'ARQ_HMAC' }
  def prop = results.findAll { it.condition == cond.name && it.protocol == 'Proposed' }
  METRICS.eachWithIndex { m, i ->
    double a = mean(base.collect(m.f)), b = mean(prop.collect(m.f))
    String change = a ? String.format("%+.1f%%", 100.0 * (b - a) / Math.abs(a)) : (b ? 'n/a' : '0.0%')
    int better = 0, equal = 0, worse = 0
    base.each { rb ->
      def rp = prop.find { it.load == rb.load && it.seed == rb.seed }
      double x = m.f(rb), y = m.f(rp)
      if (Math.abs(x - y) < 1e-9) equal++
      else if ((y > x) == m.higherIsBetter) better++
      else worse++
    }
    say String.format(" %-12s %-22s %10.3f %10.3f %9s %10s", i == 0 ? cond.name : '', m.label, a, b, change,
      "$better/$equal/$worse")
  }
}
say ''
say ' Gen = packets generated (identical for both protocols), PDR = unique packets delivered / generated,'
say ' S = normalized throughput, ReTx = TDMA retransmissions, Coll = collisions at master / sensor frames,'
say ' Delay = mean generation-to-delivery time, TOuts = ACK timeouts, Dup = duplicate packets at master,'
say ' EstErr = mean |delay value in use - true propagation delay| over all sensors, sampled every 10 s.'
say ' Statistical significance (paired Wilcoxon test): python3 plot_results.py'

// ------------------------------------------------------------------ Files
def dir = new File('results')
dir.mkdirs()
String tag = (CONDITIONS.size() == 1 ? CONDITIONS[0].name : 'all') + (ONLY_SEEDS ? "_seeds${ONLY_SEEDS}" : '')
def cols = ['condition', 'load', 'seed', 'protocol', 'generated', 'delivered', 'pdr', 'throughput', 'retx',
            'collRate', 'delay', 'timeouts', 'duplicates', 'estErr', 'collisions', 'frames', 'guard', 'slot']
new File(dir, "runs_${tag}.csv").withWriter { w ->
  w.writeLine cols.join(',')
  results.each { r -> w.writeLine cols.collect { r[it] }.join(',') }
}
if (traceRows) {
  new File(dir, 'estimate_trace.csv').withWriter { w ->
    w.writeLine 'condition,protocol,sensor,time,true_delay,delay_in_use'
    traceRows.each { w.writeLine it.join(',') }
  }
}
println ''
println " Files written to ${dir.absolutePath}: runs_${tag}.csv" + (traceRows ? ', estimate_trace.csv' : '')
