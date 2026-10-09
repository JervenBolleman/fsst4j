package swiss.sib.swissprot.fsst4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * An immutable batch of byte strings, the unit of work for FSST. Each string is a region of a backing byte array.
 *
 * FSST supports two kinds of strings:
 * <ul>
 * <li>known length strings, which may contain any byte (including 0)</li>
 * <li>zero (null) terminated strings in the C style. In that case the terminating 0 byte is part of the string,
 * and counted in its length, exactly as the C++ library expects. The compressed strings then also end with a 0
 * byte, and decompressing returns the terminating 0 byte as well.</li>
 * </ul>
 */
public final class ByteStrings {
	private final byte[][] arrays;
	private final int[] offsets;
	private final int[] lengths;

	private ByteStrings(byte[][] arrays, int[] offsets, int[] lengths) {
		this.arrays = arrays;
		this.offsets = offsets;
		this.lengths = lengths;
	}

	/** Each array is one (known length) string. */
	public static ByteStrings of(byte[]... strings) {
		byte[][] arrays = strings.clone();
		int[] offsets = new int[arrays.length];
		int[] lengths = new int[arrays.length];
		for (int i = 0; i < arrays.length; i++) {
			lengths[i] = Objects.requireNonNull(arrays[i], "strings may not contain null").length;
		}
		return new ByteStrings(arrays, offsets, lengths);
	}

	/** Each array is one (known length) string. */
	public static ByteStrings of(List<byte[]> strings) {
		return of(strings.toArray(new byte[0][]));
	}

	/**
	 * Strings stored one after the other in one array.
	 *
	 * @param data    the backing array
	 * @param offsets the start of each string in data
	 * @param lengths the byte length of each string
	 */
	public static ByteStrings of(byte[] data, int[] offsets, int[] lengths) {
		if (offsets.length != lengths.length) {
			throw new IllegalArgumentException("offsets and lengths must have the same size");
		}
		byte[][] arrays = new byte[offsets.length][];
		for (int i = 0; i < offsets.length; i++) {
			Objects.checkFromIndexSize(offsets[i], lengths[i], data.length);
			arrays[i] = data;
		}
		return new ByteStrings(arrays, offsets.clone(), lengths.clone());
	}

	/**
	 * UTF-8 encode the strings.
	 *
	 * @param zeroTerminated if true a 0 byte is appended to each string, as needed for zero terminated compression.
	 *                       The strings may then not contain the character U+0000.
	 */
	public static ByteStrings ofUtf8(Collection<String> strings, boolean zeroTerminated) {
		byte[][] arrays = new byte[strings.size()][];
		int i = 0;
		for (String s : strings) {
			byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
			if (zeroTerminated) {
				if (s.indexOf(0) >= 0) {
					throw new IllegalArgumentException("zero terminated strings may not contain U+0000");
				}
				utf8 = Arrays.copyOf(utf8, utf8.length + 1);
			}
			arrays[i++] = utf8;
		}
		return new ByteStrings(arrays, new int[arrays.length], lengthsOf(arrays));
	}

	/**
	 * Split a buffer containing consecutive zero terminated strings (e.g. "ab\0cde\0") into strings. Each string
	 * includes its terminating 0 byte. Trailing bytes without a terminating 0 are treated as an error.
	 */
	public static ByteStrings splitZeroTerminated(byte[] data, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, data.length);
		List<int[]> found = new ArrayList<>();
		int start = offset;
		int end = offset + length;
		for (int i = offset; i < end; i++) {
			if (data[i] == 0) {
				found.add(new int[] { start, i + 1 - start });
				start = i + 1;
			}
		}
		if (start != end) {
			throw new IllegalArgumentException("the last string is not zero terminated");
		}
		int[] offsets = new int[found.size()];
		int[] lengths = new int[found.size()];
		for (int i = 0; i < offsets.length; i++) {
			offsets[i] = found.get(i)[0];
			lengths[i] = found.get(i)[1];
		}
		return of(data, offsets, lengths);
	}

	/** Split on '\n', the newline itself is not part of the strings (like the FSST paper's line tests). */
	public static ByteStrings splitLines(byte[] data) {
		List<int[]> found = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < data.length; i++) {
			if (data[i] == '\n') {
				found.add(new int[] { start, i - start });
				start = i + 1;
			}
		}
		if (start < data.length) {
			found.add(new int[] { start, data.length - start });
		}
		int[] offsets = new int[found.size()];
		int[] lengths = new int[found.size()];
		for (int i = 0; i < offsets.length; i++) {
			offsets[i] = found.get(i)[0];
			lengths[i] = found.get(i)[1];
		}
		return of(data, offsets, lengths);
	}

	/** @return a copy of these strings, each with a 0 byte appended */
	public ByteStrings withZeroTerminators() {
		byte[][] copy = new byte[size()][];
		for (int i = 0; i < copy.length; i++) {
			copy[i] = new byte[lengths[i] + 1];
			System.arraycopy(arrays[i], offsets[i], copy[i], 0, lengths[i]);
		}
		return new ByteStrings(copy, new int[copy.length], lengthsOf(copy));
	}

	private static int[] lengthsOf(byte[][] arrays) {
		int[] lengths = new int[arrays.length];
		for (int i = 0; i < arrays.length; i++) {
			lengths[i] = arrays[i].length;
		}
		return lengths;
	}

	public int size() {
		return lengths.length;
	}

	public byte[] array(int i) {
		return arrays[i];
	}

	public int offset(int i) {
		return offsets[i];
	}

	public int length(int i) {
		return lengths[i];
	}

	public long totalLength() {
		long total = 0;
		for (int l : lengths) {
			total += l;
		}
		return total;
	}

	/** @return a copy of string i */
	public byte[] get(int i) {
		return Arrays.copyOfRange(arrays[i], offsets[i], offsets[i] + lengths[i]);
	}

	/**
	 * Checks the strings are valid input for zero terminated compression: each string is either empty or ends with
	 * its only 0 byte. The C++ implementation does not check this and silently corrupts strings with embedded zero
	 * bytes.
	 */
	void checkZeroTerminated() {
		for (int i = 0; i < lengths.length; i++) {
			int len = lengths[i];
			if (len == 0) {
				continue;
			}
			byte[] a = arrays[i];
			int off = offsets[i];
			if (a[off + len - 1] != 0) {
				throw new IllegalArgumentException("string " + i + " is not zero terminated");
			}
			for (int j = off; j < off + len - 1; j++) {
				if (a[j] == 0) {
					throw new IllegalArgumentException("string " + i + " contains a 0 byte before its end");
				}
			}
		}
	}
}
