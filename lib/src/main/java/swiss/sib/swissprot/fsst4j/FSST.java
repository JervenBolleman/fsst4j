package swiss.sib.swissprot.fsst4j;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Entry point for FSST (Fast Static Symbol Table) compression of batches of strings.
 *
 * Three implementations are available, which all produce identical output:
 * <ul>
 * <li>{@link Implementation#JAVA}: pure java, see {@link FsstEncoder}</li>
 * <li>{@link Implementation#JAVA_VECTOR}: pure java, compressing with the jdk.incubator.vector API (java 25+)</li>
 * <li>{@link Implementation#NATIVE}: the C++ libfsst via the FFM API, see NativeFsst (java 25+)</li>
 * </ul>
 * Decompression is always done in java, see {@link FsstDecoder}.
 */
public final class FSST {

	public enum Implementation {
		JAVA, JAVA_VECTOR, NATIVE;

		public boolean isAvailable() {
			switch (this) {
			case JAVA_VECTOR:
				return Accelerators.vectorSupported();
			case NATIVE:
				return Accelerators.nativeAvailable();
			default:
				return true;
			}
		}

		/** @return why this implementation is not available, or null if it is */
		public String unavailableReason() {
			if (isAvailable()) {
				return null;
			}
			return this == NATIVE ? Accelerators.nativeUnavailableReason() : Accelerators.vectorUnavailableReason();
		}
	}

	private FSST() {
	}

	/** Compress known length strings (UTF-8 encoded) with the pure java implementation. */
	public static FsstCompressedData compress(String[] strings) {
		return compress(Arrays.asList(strings));
	}

	/** Compress known length strings (UTF-8 encoded) with the pure java implementation. */
	public static FsstCompressedData compress(Collection<String> strings) {
		return compress(strings, false, Implementation.JAVA);
	}

	/**
	 * Compress UTF-8 encoded strings.
	 *
	 * @param zeroTerminated if true each string is compressed with a terminating 0 byte (C string style)
	 */
	public static FsstCompressedData compress(Collection<String> strings, boolean zeroTerminated,
			Implementation implementation) {
		return compress(ByteStrings.ofUtf8(strings, zeroTerminated), zeroTerminated, implementation);
	}

	/**
	 * Build a symbol table from the strings and compress them.
	 *
	 * @param zeroTerminated if true each (non empty) string must end with its only 0 byte, see {@link ByteStrings}
	 */
	public static FsstCompressedData compress(ByteStrings strings, boolean zeroTerminated,
			Implementation implementation) {
		switch (implementation) {
		case JAVA_VECTOR:
			return FsstEncoder.build(strings, zeroTerminated).compress(strings, FsstEncoder.Kernel.VECTOR);
		case NATIVE:
			return Accelerators.nativeCompress(strings, zeroTerminated);
		default:
			return FsstEncoder.build(strings, zeroTerminated).compress(strings, FsstEncoder.Kernel.SCALAR);
		}
	}

	/**
	 * A batch of compressed strings (a record, but written as a class for java 11).
	 *
	 * @param compressedLengths the length of each compressed string
	 * @param compressedData    the compressed strings, one after the other
	 * @param encoderSerialized the symbol table, as serialized by fsst_export()
	 */
	public static final class FsstCompressedData {
		private final int[] compressedLengths;
		private final byte[] compressedData;
		private final byte[] encoderSerialized;

		public FsstCompressedData(int[] compressedLengths, byte[] compressedData, byte[] encoderSerialized) {
			this.compressedLengths = Objects.requireNonNull(compressedLengths);
			this.compressedData = Objects.requireNonNull(compressedData);
			this.encoderSerialized = Objects.requireNonNull(encoderSerialized);
		}

		public int[] compressedLengths() {
			return compressedLengths;
		}

		public byte[] compressedData() {
			return compressedData;
		}

		public byte[] encoderSerialized() {
			return encoderSerialized;
		}

		public int size() {
			return compressedLengths.length;
		}

		/** @return the start of each compressed string in compressedData */
		public int[] offsets() {
			int[] offsets = new int[compressedLengths.length];
			int pos = 0;
			for (int i = 0; i < offsets.length; i++) {
				offsets[i] = pos;
				pos += compressedLengths[i];
			}
			return offsets;
		}

		public FsstDecoder decoder() {
			return FsstDecoder.importTable(encoderSerialized, 0);
		}

		/** @return the decompressed strings as bytes (with terminating 0 byte for zero terminated strings) */
		public List<byte[]> decode() {
			FsstDecoder decoder = decoder();
			int[] offsets = offsets();
			return new Decompressing<>(size()) {
				@Override
				public byte[] get(int index) {
					Objects.checkIndex(index, size());
					return decoder.decompress(compressedData, offsets[index], compressedLengths[index]);
				}
			};
		}

		/** @return the decompressed strings as UTF-8 text (without terminating 0 for zero terminated strings) */
		public List<String> decodeAsStrings() {
			FsstDecoder decoder = decoder();
			int[] offsets = offsets();
			return new Decompressing<>(size()) {
				@Override
				public String get(int index) {
					Objects.checkIndex(index, size());
					return decoder.decompressToString(compressedData, offsets[index], compressedLengths[index]);
				}
			};
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof FsstCompressedData)) {
				return false;
			}
			FsstCompressedData other = (FsstCompressedData) o;
			return Arrays.equals(compressedLengths, other.compressedLengths)
					&& Arrays.equals(compressedData, other.compressedData)
					&& Arrays.equals(encoderSerialized, other.encoderSerialized);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(compressedData);
		}

		@Override
		public String toString() {
			return "FsstCompressedData[strings=" + size() + ", compressedBytes=" + compressedData.length
					+ ", tableBytes=" + encoderSerialized.length + "]";
		}
	}

	/** An unmodifiable list that decompresses its elements on access. */
	private abstract static class Decompressing<T> extends AbstractList<T> {
		private final int size;

		Decompressing(int size) {
			this.size = size;
		}

		@Override
		public int size() {
			return size;
		}
	}
}
