package swiss.sib.swissprot.fsst4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import nl.cwi.da.fsst.fsst;

public class FSST {
	private static final byte[] FSST_CORRUPT = new byte[] { 'c', 'o', 'r', 'r', 'u', 'p', 't' };
	static {
		try {
			File fsstTmp = File.createTempFile("libfsst", ".so");
			fsstTmp.deleteOnExit();
			String arch = ManagementFactory.getOperatingSystemMXBean().getArch();
			String osName = ManagementFactory.getOperatingSystemMXBean().getName();
			try (InputStream in = FSST.class.getResourceAsStream('/'+osName + '/' + arch + "/libfsst.so")) {
				Files.copy(in, fsstTmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			System.load(fsstTmp.getAbsolutePath());
		} catch (IOException e) {
			System.loadLibrary("fsst");
		}
	}

	public static FsstCompressedData compress(String[] strings) {
		return compress(Arrays.asList(strings));
	}

	public static FsstCompressedData compress(Collection<String> asList) {
		int numberOfStrings = asList.size();
		try (Arena arena = Arena.ofConfined()) {

			long[] forLenIn = new long[numberOfStrings];
			int totalLen = 0;
			MemorySegment strIn = arena.allocate(fsst.C_POINTER.byteSize() * numberOfStrings);
			Iterator<String> iterator = asList.iterator();
			for (int i = 0; i < numberOfStrings; i++) {
				MemorySegment strInRaw = arena.allocateUtf8String(iterator.next());
				strIn.setAtIndex(fsst.C_POINTER, i, strInRaw);
				forLenIn[i] = strInRaw.byteSize();
				totalLen += strInRaw.byteSize();
			}

			MemorySegment lenIn = arena.allocateArray(fsst.size_t, forLenIn);
			final MemorySegment encoder = fsst.fsst_create((long) numberOfStrings, lenIn, strIn, 1);
			final MemorySegment compressedLengthsAsSegment = arena.allocate(totalLen * fsst.size_t.byteSize()); // lenOut
			final MemorySegment compressedStartPointersAsSegment = arena.allocate(totalLen * fsst.C_POINTER.byteSize()); // strOut
			long compressedSegmentSize = 7 + (2 * totalLen * fsst.size_t.byteSize()); // outsize
			final MemorySegment compressedSegment = arena.allocate(compressedSegmentSize); // output

			long compressedStrings = fsst.fsst_compress(encoder, numberOfStrings, lenIn, strIn, compressedSegmentSize,
					compressedSegment, compressedLengthsAsSegment, compressedStartPointersAsSegment);
			while (compressedStrings < numberOfStrings) {
				throw new IllegalStateException("Not yet implemented");
			}

			int compressedSize = 0;
			int[] compressedLengths = new int[numberOfStrings];
			for (int i = 0; i < numberOfStrings; i++) {
				long compressedLength = compressedLengthsAsSegment.getAtIndex(fsst.size_t, i);
				compressedLengths[i] = (int) compressedLength;
				compressedSize += compressedLength;
			}
			byte[] compressedOutput = compressedSegment.asSlice(0, compressedSize).toArray(ValueLayout.JAVA_BYTE);

			MemorySegment encoderState = arena.allocate(2048);
			int encoderSize = fsst.fsst_export(encoder, encoderState);
			byte[] encoderSerialized = encoderState.asSlice(0, encoderSize).toArray(ValueLayout.JAVA_BYTE);
			fsst.fsst_destroy(encoder);

			return new FsstCompressedData(compressedLengths, compressedOutput, encoderSerialized);
		}
	}

	public record FsstCompressedData(int[] compressedLengths, byte[] compressedData, byte[] encoderSerialized) {

		public List<String> decodeAsStrings() {
			return new DecompressingOnDemandList(this);
		}
	}

	public static final class DecompressingOnDemandList extends AbstractList<String> {

		private final byte[][] decoderSymbols;
		private final FsstCompressedData fsstCompressedData;

		private DecompressingOnDemandList(FsstCompressedData fsstCompressedData) {
			this.fsstCompressedData = fsstCompressedData;
			ByteBuffer wrap = ByteBuffer.wrap(fsstCompressedData.encoderSerialized).order(ByteOrder.LITTLE_ENDIAN);
			assert wrap.getLong() >> 32 == 20190218;
			boolean zeroTerminated = wrap.get(8) != 0;
			int[] lengthHisto = new int[8];
			lengthHisto[0] = wrap.get(9) & 0xff;
			lengthHisto[1] = wrap.get(10) & 0xff;
			lengthHisto[2] = wrap.get(11) & 0xff;
			lengthHisto[3] = wrap.get(12) & 0xff;
			lengthHisto[4] = wrap.get(13) & 0xff;
			lengthHisto[5] = wrap.get(14) & 0xff;
			lengthHisto[6] = wrap.get(15) & 0xff;
			lengthHisto[7] = wrap.get(16) & 0xff;
			int code = 0;
			if (zeroTerminated) {
				lengthHisto[0]--;
				code = 1;
			}
			int[] decoderLengths = new int[256];
			decoderSymbols = new byte[256][];
			decoderSymbols[0] = new byte[] {};
			wrap.position(17);
			for (int l = 1; l <= 8; l++) {
				for (int i = 0; i < (lengthHisto[l & 7]); i++) {
					int len = (l & 7) + 1;
					decoderLengths[code] = len;
					decoderSymbols[code] = new byte[len];
					for (int j = 0; j < len; j++) {
						decoderSymbols[code][j] = wrap.get();
					}
					code++;
				}
			}
			if (zeroTerminated) {
				lengthHisto[0]++;
			}
			while (code < 255) {
				decoderSymbols[code++] = FSST_CORRUPT;
			}
		}

		@Override
		public int size() {
			return fsstCompressedData.compressedLengths.length;
		}

		@Override
		public boolean addAll(Collection<? extends String> c) {
			throw new UnsupportedOperationException("Not modifiable");
		}

		@Override
		public String get(int index) {
			Objects.checkIndex(index, size());
			int offsetToRequestedString = 0;
			int compressedLength = fsstCompressedData.compressedLengths[0];
			for (int i = 0; i < index && i < fsstCompressedData.compressedLengths.length; i++) {
				compressedLength = fsstCompressedData.compressedLengths[i];
				offsetToRequestedString += compressedLength;
			}
			byte[] decompressed = new byte[256];
			int j = 0;
			for (int i = offsetToRequestedString; i < offsetToRequestedString + compressedLength; i++) {
				byte r = fsstCompressedData.compressedData[i];
				if ((r & 0xff) == 255) {
					decompressed = grow(decompressed, 1, j);
					decompressed[j++] = fsstCompressedData.compressedData[i++];
				} else {
					byte[] rawSymbol = decoderSymbols[r];
					decompressed = grow(decompressed, rawSymbol.length, j);
					System.arraycopy(rawSymbol, 0, decompressed, j, rawSymbol.length);
					j += rawSymbol.length;
				}
			}
			return new String(decompressed, 0, j, StandardCharsets.UTF_8);
		}

		private byte[] grow(byte[] decompressed, int neededSpace, int usedSpace) {
			if (decompressed.length < neededSpace + usedSpace) {
				return Arrays.copyOf(decompressed, decompressed.length * 2);
			}
			return decompressed;
		}
	}

}
