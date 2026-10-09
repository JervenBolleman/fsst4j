package swiss.sib.swissprot.fsst4j;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;

/**
 * Access to the implementations that need a newer java: the native library (FFM API) and the vector kernel
 * (jdk.incubator.vector). This is the java 25 version of the multi-release jar, the java 11 version
 * (src/main/java11) reports both as unavailable.
 */
final class Accelerators {
	private Accelerators() {
	}

	static boolean vectorSupported() {
		return FsstVectorCompressor.isSupported();
	}

	static boolean vectorPreferred() {
		return FsstVectorCompressor.isPreferred();
	}

	static String vectorUnavailableReason() {
		return "run java with --add-modules jdk.incubator.vector";
	}

	static int vectorCompress(SymbolTable st, ByteStrings strings, int first, int count, byte[] out, int outOff,
			int outLen, int[] lenOut, int[] offOut) {
		if (!FsstVectorCompressor.isSupported()) {
			throw new UnsupportedOperationException("the vector kernel " + vectorUnavailableReason());
		}
		return FsstVectorCompressor.compress(st, strings, first, count, out, outOff, outLen, lenOut, offOut);
	}

	static boolean nativeAvailable() {
		return NativeFsst.isAvailable();
	}

	static boolean nativeDecompressAvailable() {
		return NativeFsst.hasNativeDecompress();
	}

	static String nativeUnavailableReason() {
		return "native library could not be loaded: " + NativeFsst.loadError();
	}

	static FsstCompressedData nativeCompress(ByteStrings strings, boolean zeroTerminated) {
		try (NativeFsst.Encoder encoder = NativeFsst.Encoder.create(strings, zeroTerminated)) {
			return encoder.compress(strings);
		}
	}

	static int nativeDecompress(byte[] table, byte[] in, int offset, int length, byte[] out, int outOffset,
			int size) {
		try (NativeFsst.Decoder decoder = NativeFsst.Decoder.importTable(table, 0)) {
			return decoder.decompress(in, offset, length, out, outOffset, size);
		}
	}
}
