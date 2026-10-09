package swiss.sib.swissprot.fsst4j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;
import swiss.sib.swissprot.fsst4j.FsstEncoder.Kernel;

/**
 * Runs the dbtext corpus of the FSST paper (fsst/paper/dbtext) line by line through all implementations, the same
 * way the paper's linetest/filtertest tools use FSST. The java implementations must produce byte for byte the same
 * symbol table and compressed strings as the C++ library, and every implementation must round trip.
 */
class CorpusCompatibilityTest {

	static Stream<Arguments> corpus() {
		return TestSupport.corpusFiles().flatMap(f -> Stream.of(Arguments.of(f, false), Arguments.of(f, true)));
	}

	private static ByteStrings lines(Path file, boolean zeroTerminated) throws IOException {
		ByteStrings lines = ByteStrings.splitLines(Files.readAllBytes(file));
		return zeroTerminated ? lines.withZeroTerminators() : lines;
	}

	@ParameterizedTest(name = "{0} zeroTerminated={1}")
	@MethodSource("corpus")
	void javaMatchesNative(Path file, boolean zeroTerminated) throws IOException {
		ByteStrings lines = lines(file, zeroTerminated);
		FsstEncoder encoder = FsstEncoder.build(lines, zeroTerminated);
		FsstCompressedData scalar = encoder.compress(lines, Kernel.SCALAR);
		assertRoundTrip(lines, scalar);

		if (FsstVectorCompressor.isSupported()) {
			FsstCompressedData vector = encoder.compress(lines, Kernel.VECTOR);
			assertArrayEquals(scalar.compressedLengths(), vector.compressedLengths(), "vector kernel lengths");
			assertArrayEquals(scalar.compressedData(), vector.compressedData(), "vector kernel output");
		}

		assumeTrue(NativeFsst.isAvailable(), "native library not available");
		try (NativeFsst.Encoder nativeEncoder = NativeFsst.Encoder.create(lines, zeroTerminated)) {
			assertArrayEquals(nativeEncoder.exportTable(), encoder.exportTable(), "symbol table");
			FsstCompressedData nativeData = nativeEncoder.compress(lines);
			assertArrayEquals(nativeData.compressedLengths(), scalar.compressedLengths(), "compressed lengths");
			assertArrayEquals(nativeData.compressedData(), scalar.compressedData(), "compressed data");
		}
		if (NativeFsst.hasNativeDecompress()) {
			assertNativeRoundTrip(lines, scalar);
		}
	}

	static void assertRoundTrip(ByteStrings lines, FsstCompressedData compressed) {
		FsstDecoder decoder = compressed.decoder();
		int[] offsets = compressed.offsets();
		for (int i = 0; i < lines.size(); i++) {
			byte[] decompressed = decoder.decompress(compressed.compressedData(), offsets[i],
					compressed.compressedLengths()[i]);
			assertArrayEquals(lines.get(i), decompressed, "string " + i);
		}
	}

	static void assertNativeRoundTrip(ByteStrings lines, FsstCompressedData compressed) {
		try (NativeFsst.Decoder decoder = NativeFsst.Decoder.importTable(compressed.encoderSerialized(), 0)) {
			int[] offsets = compressed.offsets();
			for (int i = 0; i < lines.size(); i++) {
				byte[] out = new byte[lines.length(i) + 64];
				int len = decoder.decompress(compressed.compressedData(), offsets[i],
						compressed.compressedLengths()[i], out, 0, out.length);
				assertEquals(lines.length(i), len, "string " + i);
				assertArrayEquals(lines.get(i), java.util.Arrays.copyOf(out, len), "string " + i);
			}
		}
	}
}
