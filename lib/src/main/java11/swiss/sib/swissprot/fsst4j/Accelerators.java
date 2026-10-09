package swiss.sib.swissprot.fsst4j;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;

/**
 * Access to the implementations that need a newer java: the native library (FFM API, java 22+) and the vector kernel
 * (jdk.incubator.vector). This is the java 11 version, in which neither is available. The java 25 version in the
 * multi-release jar (src/main/java25) delegates to {@link NativeFsst} and {@link FsstVectorCompressor}.
 */
final class Accelerators {
	private static final String REQUIRES_JAVA_25 = "requires java 25 or later (these are the java 11 classes of the"
			+ " multi-release jar, running on java " + System.getProperty("java.specification.version") + ")";

	private Accelerators() {
	}

	static boolean vectorSupported() {
		return false;
	}

	static boolean vectorPreferred() {
		return false;
	}

	static String vectorUnavailableReason() {
		return REQUIRES_JAVA_25;
	}

	static int vectorCompress(SymbolTable st, ByteStrings strings, int first, int count, byte[] out, int outOff,
			int outLen, int[] lenOut, int[] offOut) {
		throw new UnsupportedOperationException("the vector kernel " + REQUIRES_JAVA_25);
	}

	static boolean nativeAvailable() {
		return false;
	}

	static boolean nativeDecompressAvailable() {
		return false;
	}

	static String nativeUnavailableReason() {
		return REQUIRES_JAVA_25;
	}

	static FsstCompressedData nativeCompress(ByteStrings strings, boolean zeroTerminated) {
		throw new UnsupportedOperationException("the native library " + REQUIRES_JAVA_25);
	}

	static int nativeDecompress(byte[] table, byte[] in, int offset, int length, byte[] out, int outOffset,
			int size) {
		throw new UnsupportedOperationException("the native library " + REQUIRES_JAVA_25);
	}
}
