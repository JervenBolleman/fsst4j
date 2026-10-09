package swiss.sib.swissprot.fsst4j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;
import swiss.sib.swissprot.fsst4j.FsstEncoder.Kernel;

class EdgeCaseTest {

	/** Generators of string batches with different properties. */
	static Stream<Arguments> batches() {
		List<Arguments> args = new ArrayList<>();
		for (long seed = 0; seed < 6; seed++) {
			long s = seed;
			args.add(Arguments.of("random binary " + s, randomStrings(s, 2000, 0, 40, 256)));
			args.add(Arguments.of("small alphabet " + s, randomStrings(s, 3000, 0, 100, 4)));
			args.add(Arguments.of("long strings " + s, randomStrings(s, 40, 400, 5000, 16)));
		}
		args.add(Arguments.of("one huge string", randomStrings(1, 1, 300_000, 300_001, 26)));
		args.add(Arguments.of("all equal", Collections.nCopies(5000, "abcabcabcabc".getBytes())));
		args.add(Arguments.of("all empty", Collections.nCopies(100, new byte[0])));
		args.add(Arguments.of("single byte runs", randomRuns(3)));
		args.add(Arguments.of("terminator above 127", rareHighTerminator()));
		args.add(Arguments.of("terminator above 127 in symbols", terminatorInSymbols()));
		args.add(Arguments.of("tiny", List.of("a".getBytes())));
		args.add(Arguments.of("no strings", List.of()));
		return args.stream();
	}

	private static final class Collections {
		static List<byte[]> nCopies(int n, byte[] value) {
			return java.util.Collections.nCopies(n, value);
		}
	}

	static List<byte[]> randomStrings(long seed, int n, int minLen, int maxLen, int alphabet) {
		Random r = new Random(seed);
		List<byte[]> strings = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			byte[] s = new byte[minLen + r.nextInt(maxLen - minLen)];
			for (int j = 0; j < s.length; j++) {
				// skewed distribution so there is something to compress
				int v = (int) (Math.abs(r.nextGaussian()) * alphabet / 3);
				s[j] = (byte) (alphabet == 256 ? v : 'a' + Math.min(v, alphabet - 1));
			}
			strings.add(s);
		}
		return strings;
	}

	/**
	 * Every byte value occurs, byte 200 least often, so it becomes the terminator. The C++ code compares the
	 * terminator with a (signed) char, so on x86 symbols may then contain the terminator byte.
	 */
	static List<byte[]> rareHighTerminator() {
		List<byte[]> strings = new ArrayList<>(randomStrings(11, 3000, 5, 30, 6));
		for (int b = 0; b < 256; b++) {
			int copies = b == 200 ? 1 : 40;
			for (int c = 0; c < copies; c++) {
				strings.add(new byte[] { 'a', (byte) b, (byte) b, 'b', (byte) b, (byte) 200, 'c' });
			}
		}
		strings.add(new byte[] { (byte) 200, (byte) 200, (byte) 200, 'a', 'b' });
		return strings;
	}

	/**
	 * A small batch (no sampling) in which every byte value occurs, byte 200 least often, but in repeated pairs so it
	 * ends up in multi-byte symbols. A signed char C++ build excludes no symbols starting with it, an unsigned char
	 * build does: this case detects a libfsst that was not built with -fsigned-char.
	 */
	static List<byte[]> terminatorInSymbols() {
		List<byte[]> strings = new ArrayList<>();
		for (int b = 0; b < 256; b++) {
			if (b != 200) {
				strings.add(new byte[] { (byte) b, (byte) b, (byte) b, (byte) b });
			}
		}
		for (int i = 0; i < 3; i++) {
			strings.add(new byte[] { 'x', (byte) 200, 'q', 'q' });
		}
		strings.addAll(randomStrings(13, 400, 2, 12, 3));
		return strings;
	}

	static List<byte[]> randomRuns(long seed) {
		Random r = new Random(seed);
		List<byte[]> strings = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			byte[] s = new byte[r.nextInt(50)];
			Arrays.fill(s, (byte) r.nextInt(256));
			strings.add(s);
		}
		return strings;
	}

	/** zero terminated variant: drop 0 bytes and append a terminator */
	static ByteStrings zeroTerminated(List<byte[]> strings) {
		List<byte[]> zt = new ArrayList<>();
		for (byte[] s : strings) {
			byte[] withoutZeros = new byte[s.length + 1];
			int j = 0;
			for (byte b : s) {
				if (b != 0) {
					withoutZeros[j++] = b;
				}
			}
			zt.add(Arrays.copyOf(withoutZeros, j + 1));
		}
		return ByteStrings.of(zt);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("batches")
	void knownLength(String name, List<byte[]> strings) {
		check(ByteStrings.of(strings), false);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("batches")
	void zeroTerminated(String name, List<byte[]> strings) {
		check(zeroTerminated(strings), true);
	}

	private static void check(ByteStrings strings, boolean zeroTerminated) {
		FsstEncoder encoder = FsstEncoder.build(strings, zeroTerminated);
		FsstCompressedData scalar = encoder.compress(strings, Kernel.SCALAR);
		CorpusCompatibilityTest.assertRoundTrip(strings, scalar);
		if (FsstVectorCompressor.isSupported()) {
			FsstCompressedData vector = encoder.compress(strings, Kernel.VECTOR);
			assertEquals(scalar, vector, "vector kernel");
		}
		if (NativeFsst.isAvailable()) {
			try (NativeFsst.Encoder nativeEncoder = NativeFsst.Encoder.create(strings, zeroTerminated)) {
				assertArrayEquals(nativeEncoder.exportTable(), encoder.exportTable(), "symbol table");
				assertEquals(nativeEncoder.compress(strings), scalar, "native compressed data");
			}
			if (NativeFsst.hasNativeDecompress()) {
				CorpusCompatibilityTest.assertNativeRoundTrip(strings, scalar);
			}
		}
	}

	/**
	 * fsst_decompress truncates when the output buffer is too small, zero terminated strings then get a 0 as last
	 * byte. The java decoder must behave exactly the same for every buffer size.
	 */
	@Test
	void truncatedDecompressionMatchesNative() {
		assumeTrue(NativeFsst.hasNativeDecompress());
		for (boolean zt : new boolean[] { false, true }) {
			List<byte[]> raw = randomStrings(7, 300, 0, 120, 256);
			ByteStrings strings = zt ? zeroTerminated(raw) : ByteStrings.of(raw);
			FsstCompressedData compressed = FSST.compress(strings, zt, FSST.Implementation.JAVA);
			FsstDecoder decoder = compressed.decoder();
			int[] offsets = compressed.offsets();
			try (NativeFsst.Decoder nativeDecoder = NativeFsst.Decoder.importTable(compressed.encoderSerialized(),
					0)) {
				for (int i = 0; i < strings.size(); i++) {
					int len = compressed.compressedLengths()[i];
					for (int size = 1; size < strings.length(i) + 40; size++) {
						byte[] expected = new byte[size];
						byte[] actual = new byte[size];
						int nativeLen = nativeDecoder.decompress(compressed.compressedData(), offsets[i], len, expected,
								0, size);
						int javaLen = decoder.decompress(compressed.compressedData(), offsets[i], len, actual, 0, size);
						assertEquals(nativeLen, javaLen);
						int valid = Math.min(size, nativeLen);
						assertArrayEquals(Arrays.copyOf(expected, valid), Arrays.copyOf(actual, valid),
								"string " + i + " size " + size);
					}
				}
			}
		}
	}

	@Test
	void cStringsInNativeMemory() {
		assumeTrue(NativeFsst.isAvailable());
		List<String> text = IntStream.range(0, 2000).mapToObj(i -> "http://www.uniprot.org/uniprot/P" + i).toList();
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment[] cStrings = text.stream().map(arena::allocateFrom).toArray(MemorySegment[]::new);
			assertEquals(text.get(5).length(), NativeFsst.strlen(cStrings[5]));
			try (NativeFsst.Encoder encoder = NativeFsst.Encoder.createForCStrings(cStrings)) {
				FsstCompressedData compressed = encoder.compressCStrings(cStrings);
				assertEquals(text, compressed.decodeAsStrings());
				// identical to compressing the same strings, with terminator, from java
				ByteStrings fromJava = ByteStrings.ofUtf8(text, true);
				assertEquals(FSST.compress(fromJava, true, FSST.Implementation.JAVA), compressed);
			}
		}
	}

	@Test
	void splitZeroTerminated() {
		byte[] buffer = "first\0second\0\0third\0".getBytes(StandardCharsets.UTF_8);
		ByteStrings strings = ByteStrings.splitZeroTerminated(buffer, 0, buffer.length);
		assertEquals(4, strings.size());
		assertArrayEquals("second\0".getBytes(StandardCharsets.UTF_8), strings.get(1));
		assertArrayEquals(new byte[] { 0 }, strings.get(2));
		FsstCompressedData compressed = FSST.compress(strings, true, FSST.Implementation.JAVA);
		assertEquals(List.of("first", "second", "", "third"), compressed.decodeAsStrings());
		assertThrows(IllegalArgumentException.class, () -> ByteStrings.splitZeroTerminated(buffer, 0, 3));
	}

	@Test
	void zeroTerminatedInputIsValidated() {
		ByteStrings notTerminated = ByteStrings.of("abc".getBytes());
		assertThrows(IllegalArgumentException.class, () -> FsstEncoder.build(notTerminated, true));
		ByteStrings embeddedZero = ByteStrings.of("a\0bc\0".getBytes());
		assertThrows(IllegalArgumentException.class, () -> FsstEncoder.build(embeddedZero, true));
		// known length strings may contain zeros
		FsstCompressedData compressed = FSST.compress(embeddedZero, false, FSST.Implementation.JAVA);
		assertArrayEquals("a\0bc\0".getBytes(), compressed.decode().get(0));
	}

	@Test
	void outputBufferTooSmall() {
		ByteStrings strings = ByteStrings.of(randomStrings(3, 100, 10, 20, 26));
		FsstEncoder encoder = FsstEncoder.build(strings, false);
		for (Kernel kernel : Kernel.values()) {
			if (kernel == Kernel.VECTOR && !FsstVectorCompressor.isSupported()) {
				continue;
			}
			byte[] out = new byte[200];
			int[] lenOut = new int[100];
			int[] offOut = new int[100];
			int done = encoder.compress(strings, 0, 100, out, 0, out.length, lenOut, offOut, kernel);
			assertEquals(true, done > 0 && done < 100, kernel + " compressed " + done);
			FsstDecoder decoder = encoder.decoder();
			for (int i = 0; i < done; i++) {
				assertArrayEquals(strings.get(i), decoder.decompress(out, offOut[i], lenOut[i]));
			}
		}
	}

	@Test
	void terminatorAbove127() {
		ByteStrings strings = ByteStrings.of(rareHighTerminator());
		FsstEncoder encoder = FsstEncoder.build(strings, false);
		assertTrue(encoder.st.terminator >= 128, "terminator " + encoder.st.terminator);
	}

	@Test
	void terminatorInSymbolsUsesHighTerminator() {
		FsstEncoder encoder = FsstEncoder.build(ByteStrings.of(terminatorInSymbols()), false);
		assertEquals(200, encoder.st.terminator);
	}

	@Test
	void corruptHeaderIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> FsstDecoder.importTable(new byte[100], 0));
	}
}
