package swiss.sib.swissprot.fsst4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout.OfChar;
import java.lang.foreign.ValueLayout.OfLong;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

import nl.cwi.da.fsst.fsst;

public class FSST {

	static {
		System.loadLibrary("fsst");
	}

	public static FsstCompressedData compress(String[] strings) {
		return compress(Arrays.asList(strings));
	}

	public static FsstCompressedData compress(Collection<String> asList) {
		int numberOfStrings = asList.size();
		try (Arena arena = Arena.ofConfined()) {

			long[] forLenIn = new long[numberOfStrings];
			int totalLen = 0;
			try (ByteArrayOutputStream boas = new ByteArrayOutputStream()) {
				{
					int i = 0;
					for (Iterator<String> iterator = asList.iterator(); iterator.hasNext();) {
						String st = iterator.next();
						final byte[] bytes = st.getBytes(StandardCharsets.UTF_8);
						boas.writeBytes(bytes);
						forLenIn[i++] = bytes.length;
						totalLen += bytes.length;
					}
				}
				MemorySegment lenIn = arena.allocateArray(fsst.size_t, forLenIn);
				MemorySegment strIn = arena.allocate(fsst.C_POINTER.byteSize() * numberOfStrings);
				byte[] byteArray = boas.toByteArray();
				MemorySegment strInRaw = arena.allocateArray(fsst.C_CHAR, byteArray);
				for (int off = 0, i = 0; i < numberOfStrings; i++) {
					strIn.setAtIndex(fsst.C_POINTER, i, strInRaw.asSlice(off));
					off += forLenIn[i];
				}

				final MemorySegment encoder = fsst.fsst_create((long) numberOfStrings, lenIn, strIn, 0);

				final MemorySegment compressedLengthsAsSegment = arena.allocate(totalLen * fsst.size_t.byteSize());
				final MemorySegment compressedStartPointersAsSegment = arena
						.allocate(totalLen * fsst.size_t.byteSize());
				long compressedSegmentSize = 7 + (2 * totalLen * fsst.size_t.byteSize());
				final MemorySegment compressedSegment = arena.allocate(compressedSegmentSize);

				long compressedStrings = fsst.fsst_compress(encoder, numberOfStrings, lenIn, strIn,
						compressedSegmentSize, compressedSegment, compressedLengthsAsSegment,
						compressedStartPointersAsSegment);
				while (compressedStrings < numberOfStrings) {
					throw new IllegalStateException("Not yet implemented");
				}

				int compressedSize = 0;
				long[] compressedLengths = new long[numberOfStrings];
				for (int i = 0; i < numberOfStrings; i++) {
					long compressedLength = compressedLengthsAsSegment.getAtIndex(fsst.size_t, i);
					compressedLengths[i] = compressedLength;
					compressedSize += compressedLength;
				}
				byte[] compressedOutput = new byte[compressedSize];
				compressedSegment.asByteBuffer().get(compressedOutput);
				fsst.fsst_destroy(encoder);

				return new FsstCompressedData(compressedLengths, compressedOutput);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}

		}
	}

	public record FsstCompressedData(long[] compressedLengths, byte[] compressedData) {
	}
}
