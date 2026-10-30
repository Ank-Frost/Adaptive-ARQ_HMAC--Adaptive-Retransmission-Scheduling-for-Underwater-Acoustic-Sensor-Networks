package arqhmac

/**
 * Scenario definition shared by all simulation scripts: topology, node motion and traffic.
 *
 * Topology (all nodes at 50 m depth; distances scale with the network range R):
 *   - Master (gateway) moored at the centre (0, 0).
 *   - 4 moored sensors at 0.38R - 0.75R.
 *   - 6 AUV sensors patrolling radially between 0.2R and 0.85R, each on its own bearing and phase,
 *     so their propagation delay to the master changes continuously. All nodes stay within range R.
 *
 * Traffic: Poisson arrivals at G / (n * td) packets/s per sensor (network offered load G). A trace
 * is fully determined by (seed, load, sensor), so both protocols can be run on exactly the same packets.
 */
class Scenario {

  static final int    NUM_SENSORS = 10
  static final double SOUND_SPEED = 1500.0
  static final double DEPTH       = -50.0
  static final double TD          = 1.0      // DATA frame duration (1200 bits @ 1200 bps)
  static final double T_ACK       = 0.1      // ACK/NACK frame duration (120 bits @ 1200 bps)

  // [bearing (deg from north), radius / R]
  static final List MOORED = [[20, 0.375], [110, 0.75], [200, 0.55], [290, 0.65]]
  // [bearing (deg from north), start radius / R, +1 = heading outward, -1 = heading inward]
  static final List AUVS   = [[65, 0.25, 1], [155, 0.8, -1], [245, 0.5, 1],
                              [335, 0.65, -1], [45, 0.75, 1], [225, 0.3, -1]]
  static final double R_MIN = 0.2, R_MAX = 0.85

  /** Sensors with address, type and start position (m) for a network of range R. */
  static List<Map> sensors(double range) {
    List<Map> list = []
    MOORED.each { s -> list << sensor(list.size() + 2, 'moored', s[0], s[1] * range, 0) }
    AUVS.each   { s -> list << sensor(list.size() + 2, 'AUV',    s[0], s[1] * range, s[2]) }
    list
  }

  private static Map sensor(int addr, String kind, double bearing, double r, int dir) {
    double b = Math.toRadians(bearing)
    [addr: addr, kind: kind, bearing: bearing, r0: r, dir: dir,
     x: r * Math.sin(b), y: r * Math.cos(b), z: DEPTH]
  }

  /**
   * Radial patrol between R_MIN*R and R_MAX*R as UnetStack motion legs [time s, speed m/s, yaw deg].
   * UnetStack yaw: 0 = east (+x), counter-clockwise.
   */
  static List<Map> patrolLegs(Map s, double range, double speed, double duration) {
    List<Map> legs = []
    double t = 0, r = s.r0
    int d = s.dir
    while (t < duration) {
      double target = (d > 0 ? R_MAX : R_MIN) * range
      double yaw = ((90 - s.bearing) + (d > 0 ? 0 : 180) + 720) % 360
      legs << [time: t, speed: speed, yaw: yaw]
      t += Math.abs(target - r) / speed
      r = target
      d = -d
    }
    legs
  }

  /** Length of the INIT phase, Eq. (1). Traffic starts after it. */
  static double tInitial(double range) {
    NUM_SENSORS * (2 * range / SOUND_SPEED + TD + T_ACK)
  }

  /**
   * Scrambles a seed (MurmurHash3 64-bit finaliser). java.util.Random generators created with nearly
   * equal seeds produce nearly equal first numbers; mixing the seed first makes the streams independent.
   */
  static long mix(long z) {
    z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL
    z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L
    z ^ (z >>> 33)
  }

  /** Seed of a node's own random numbers (backoff), fixed by the run's (seed, load) and the node. */
  static long nodeSeed(long seed, double load, int addr) {
    mix(seed * 1_000_003L + Math.round(load * 1000) * 101 + addr + 777_777L)
  }

  /** Shared traffic trace: sensor address -> sorted packet generation times (s). */
  static Map<Integer, List<Double>> trace(long seed, double load, double range, double duration) {
    double rate  = load / (NUM_SENSORS * TD)
    double start = tInitial(range) + 5
    double end   = duration - 30                      // leave time for the last packets
    sensors(range).collectEntries { s ->
      def rnd = new Random(mix(seed * 1_000_003L + Math.round(load * 1000) * 101 + s.addr))
      List<Double> times = []
      double t = start
      while ((t += -Math.log(1 - rnd.nextDouble()) / rate) < end) times << t
      [(s.addr): times]
    }
  }
}
