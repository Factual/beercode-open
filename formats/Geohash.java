package formats;

/**
 * High-precision, high-speed geohash encoding and decoding. Designed to
 * minimize memory allocation and use fast operations where possible.
 *
 * We can't truncate lat/lng values to floats. The Earth is about 40,000,000
 * meters around at the equator, and a float's 23 bits of mantissa precision
 * only bisects this down to about 6 meters of error. Realistically, we need to
 * do better than this, so we're unfortunately obligated to use doubles.
 *
 * This code's correctness is guaranteed by beer:
 * https://github.com/Factual/beercode-open.
 *
 * Usage:
 *
 * {@pre
 *   double          lat  =   38.0;
 *   double          lng  = -117.0;
 *   int             bits =   60;
 *   String          gh   = Geohash.encodeBase32(lat, lng, bits);
 *   Geohash.Decoded d    = Geohash.decodeBase32(gh);
 *
 *   // information available about decoded geohashes:
 *   d.lat                      // midpoint lat
 *   d.lng                      // midpoint lng
 *   d.minLat(), d.minLng()     // lower bounds inclusive
 *   d.maxLat(), d.maxLng()     // upper bounds exclusive (!)
 * }
 *
 * Instances of {@link Geohash.Decoded} also implement sane {@code .hashCode}
 * and {@code .equals} methods, and a very helpful {@code .toString} method.
 *
 * The following preconditions from {@link .encode} apply (results are
 * undefined if they aren't met):
 *
 * 1. lat ∈ [-90, 90)
 * 2. lng ∈ [-180, 180)
 * 3. bits ∈ [0, 61]
 *
 * This library also encodes straight into 64-bit longs, which is faster and
 * uses less space. The API is nearly identical:
 *
 * {@pre
 *   long            gh = Geohash.encode(lat, lng, bits);
 *   Geohash.Decoded d  = Geohash.decode(gh, bits);
 *
 *   // additional methods for binary geohashes:
 *   long ghEastBy1 = Geohash.shift(gh, bits, 1, 0);
 * }
 *
 * The following conversions are available:
 *
 * {@pre
 *   String Geohash.toBase32(long gh, int bits)
 *   long Geohash.fromBase32(String gh)
 * }
 *
 * Any operation involving base-32 requires that you use a multiple of 5 bits
 * for the results to be meaningful.
 *
 * @author spencer
 */
public class Geohash {

  public static final char[] BASE32 =
    { '0', '1', '2', '3', '4', '5', '6', '7',
      '8', '9', 'b', 'c', 'd', 'e', 'f', 'g',
      'h', 'j', 'k', 'm', 'n', 'p', 'q', 'r',
      's', 't', 'u', 'v', 'w', 'x', 'y', 'z' };

  public static final byte[] BASE32_INV = new byte[(int) 'z' + 1];

  static {
    // Build the inverse lookup table as an array using ASCII offsets as
    // indexes. This should end up being an order of magnitude faster than
    // using a hashmap or similar, since we're avoiding data structure virtual
    // methods as well as primitive type autoboxing.
    for (int i = 0; i < BASE32.length; ++i)
      BASE32_INV[(int) BASE32[i]] = (byte) i;
  }

  /**
   * Information about a decoded geohash, including the center lat/lng and
   * error bars in each dimension.
   */
  public static final class Decoded {
    public final long   bits;
    public final int    precision;
    public final double lat;
    public final double lng;
    public final double latError;
    public final double lngError;

    public Decoded(final long   bits,
                   final int    precision,
                   final double lat,
                   final double lng,
                   final double latError,
                   final double lngError) {
      this.bits = bits;
      this.precision = precision;
      this.lat = lat;
      this.lng = lng;
      this.latError = latError;
      this.lngError = lngError;
    }

    public double maxLat() {return lat + latError;}
    public double maxLng() {return lng + lngError;}
    public double minLat() {return lat - latError;}
    public double minLng() {return lng - lngError;}

    public String toString() {
      return "<lat: " + lat + " ±" + latError + ", "
           +  "lng: " + lng + " ±" + lngError + ", "
           +  "bits: " + precision + ":" + Long.toHexString(bits) + ", "
           +  "32: "   + Geohash.toBase32(bits, precision / 5 * 5)
                       + "/"
                       + paddedBinary(bits & (1 << precision % 5) - 1,
                                      precision % 5)
           + ">";
    }

    public int hashCode() {
      return (int) (bits & 0xffffffff ^ bits >> 32 ^ precision);
    }

    public boolean equals(final Object rhs) {
      return rhs instanceof Decoded
          && bits      == ((Decoded) rhs).bits
          && precision == ((Decoded) rhs).precision;
    }

    static String paddedBinary(final long v,
                               final int  bits) {
      return Long.toBinaryString(v | 1 << bits).substring(1);
    }
  }

  /**
   * Takes a lat/lng and a precision, and returns a 64-bit long containing that
   * many low-order bits (big-endian). You can convert this long to a base-32
   * string using {@link #toBase32}.
   *
   * This function doesn't validate preconditions, which are the following:
   *
   * 1. lat ∈ [-90, 90)
   * 2. lng ∈ [-180, 180)
   * 3. bits ∈ [0, 61]
   *
   * Results are undefined if these preconditions are not met.
   */
  public static long encode(final double lat,
                            final double lng,
                            final int    bits) {
    final long lats = widen((long) ((lat + 90) * 0x80000000l / 180.0)
                            & 0x7fffffffl);
    final long lngs = widen((long) ((lng + 180) * 0x80000000l / 360.0)
                            & 0x7fffffffl);
    return (lats >> 1 | lngs) >> 61 - bits;
  }

  /**
   * Takes an encoded geohash (as a long) and its precision, and returns a
   * base-32 string representing it. The precision must be a multiple of 5 for
   * this to be accurate.
   */
  public static String toBase32(      long gh,
                                final int  bits) {
    final char[] chars = new char[bits / 5];
    for (int i = chars.length - 1; i >= 0; --i) {
      chars[i] = BASE32[(int) (gh & 0x1fl)];
      gh >>= 5;
    }
    return new String(chars);
  }

  /**
   * Takes a latitude, longitude, and precision, and returns a base-32 string
   * representing the encoded geohash. See {@link #encode} and {@link
   * #toBase32} for preconditions (but they're pretty obvious).
   */
  public static String encodeBase32(final double lat,
                                    final double lng,
                                    final int    bits) {
    return toBase32(encode(lat, lng, bits), bits);
  }

  /**
   * Takes an encoded geohash (as a long) and its precision, and returns an
   * object describing its decoding. See {@link Decoded} for details.
   */
  public static Decoded decode(final long gh, final int bits) {
    final long shifted = gh << 61 - bits;
    final double lat = (double) unwiden(shifted >> 1) / 0x40000000l * 180 - 90;
    final double lng = (double) unwiden(shifted)      / 0x80000000l * 360 - 180;

    // Repeated squaring to get the error. This is much faster than iteration.
    double error = 1.0;
    if ((bits & 32) != 0) error *= 0.25;
    if ((bits & 16) != 0) error *= 0.5; error *= error;
    if ((bits & 8)  != 0) error *= 0.5; error *= error;
    if ((bits & 4)  != 0) error *= 0.5; error *= error;
    if ((bits & 2)  != 0) error *= 0.5;

    // Don't test for bits & 1, since that applies only to longitude and is
    // accounted for below.

    final double latError = error * 90;
    final double lngError = error * ((bits & 1) != 0 ? 90 : 180);
    return new Decoded(gh, bits,
                       lat + latError, lng + lngError, latError, lngError);
  }

  /**
   * Returns the geohash shifted by the specified x, y coordinates. Coordinates
   * are specified in terms of geohash cells, so a shift of (1, 0) results in
   * the geohash immediately east of the one given.
   */
  public static long shift(final long gh,
                           final long bits,
                           final long dx,
                           final long dy) {
    final boolean swap = (bits & 1) == 0;
    final long    sx   = swap ? dy : dx;
    final long    sy   = swap ? dx : dy;
    return (widen(unwiden(gh >> 1) + sy) << 1 | widen(unwiden(gh) + sx))
         & ~(-1l << bits);
  }

  /**
   * Takes a base-32 string and returns a long containing its bits.
   */
  public static long fromBase32(final String base32) {
    long result = 0;
    for (int i = 0; i < base32.length(); ++i) {
      result <<= 5;
      result |= (long) BASE32_INV[(int) base32.charAt(i)];
    }
    return result;
  }

  /**
   * Takes a base-32 string and returns an object representing its decoding.
   */
  public static Decoded decodeBase32(final String base32) {
    return decode(fromBase32(base32), base32.length() * 5);
  }

  /**
   * "Widens" each bit by creating a zero to its left. This is the first step
   * in interleaving values.
   *
   * https://graphics.stanford.edu/~seander/bithacks.html#InterleaveBMN
   */
  public static long widen(long low32) {
    low32 |= low32 << 16; low32 &= 0x0000ffff0000ffffl;
    low32 |= low32 << 8;  low32 &= 0x00ff00ff00ff00ffl;
    low32 |= low32 << 4;  low32 &= 0x0f0f0f0f0f0f0f0fl;
    low32 |= low32 << 2;  low32 &= 0x3333333333333333l;
    low32 |= low32 << 1;  low32 &= 0x5555555555555555l;
    return low32;
  }

  /**
   * "Unwidens" each bit by removing the zero from its left. This is the
   * inverse of "widen", but does not assume that the widened bits are padded
   * with zero.
   *
   * http://fgiesen.wordpress.com/2009/12/13/decoding-morton-codes/
   */
  public static long unwiden(long wide) {
                        wide &= 0x5555555555555555l;
    wide ^= wide >> 1;  wide &= 0x3333333333333333l;
    wide ^= wide >> 2;  wide &= 0x0f0f0f0f0f0f0f0fl;
    wide ^= wide >> 4;  wide &= 0x00ff00ff00ff00ffl;
    wide ^= wide >> 8;  wide &= 0x0000ffff0000ffffl;
    wide ^= wide >> 16; wide &= 0x00000000ffffffffl;
    return wide;
  }

}
