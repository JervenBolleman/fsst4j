package swiss.sib.swissprot.fsst4j;

import static swiss.sib.swissprot.fsst4j.SymbolTable.CODE_BASE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.HASH_TAB_SIZE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.ICL_FREE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.LEN_BITS;
import static swiss.sib.swissprot.fsst4j.SymbolTable.hash;
import static swiss.sib.swissprot.fsst4j.SymbolTable.ignoredBits;
import static swiss.sib.swissprot.fsst4j.SymbolTable.length;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Port of compressSIMD() of libfsst.cpp. Strings are cut into chunks of at most 511 bytes, staged in a 256KB buffer
 * and described by "jobs". Batches of up to 512 jobs are compressed in parallel by the vector kernel
 * ({@link VectorKernel}, a port of fsst_compressAVX512() using the jdk.incubator.vector API), unfinished jobs are
 * completed with scalar code.
 *
 * This class does not reference jdk.incubator.vector itself, so it can be loaded when that module is not present.
 *
 * A job is a long with the bit fields (low-to-high) out:19, pos:9, end:18, cur:18. cur/end are input offsets in
 * symbolBase, out is the output offset in codeBase and pos the index of the job in the batch.
 */
final class FsstVectorCompressor {
	static final int SYMBOL_BASE_SIZE = 1 << 18;
	static final int CODE_BASE_SIZE = 1 << 19;
	static final int BATCH = 512;
	/** below this many non empty jobs the vector kernel is not used (32 is due to max 4x8 unrolling in C++) */
	static final int MIN_VECTOR_JOBS = 32;
	static final int PAD = 64;

	private static final ValueLayout.OfLong LONG_LE_UNALIGNED = ValueLayout.JAVA_LONG_UNALIGNED
			.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static final boolean SUPPORTED = detectSupport();
	private static final boolean PREFERRED = SUPPORTED && Boolean.getBoolean("fsst4j.vector");

	private FsstVectorCompressor() {
	}

	private static boolean detectSupport() {
		if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN
				|| ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
			return false;
		}
		try {
			return VectorKernel.isUsable();
		} catch (LinkageError e) {
			return false;
		}
	}

	/** @return true if jdk.incubator.vector is available (run with --add-modules jdk.incubator.vector) */
	static boolean isSupported() {
		return SUPPORTED;
	}

	/**
	 * @return true if the AUTO kernel should use the vector kernel. Off by default as it is slower than the scalar
	 *         kernel on current JVMs, enable with -Dfsst4j.vector=true
	 */
	static boolean isPreferred() {
		return PREFERRED;
	}

	static long job(int cur, int end, int pos, int out) {
		return ((long) cur << 46) | ((long) end << 28) | ((long) pos << 19) | out;
	}

	static int jobCur(long job) {
		return (int) (job >>> 46);
	}

	static int jobEnd(long job) {
		return (int) (job >>> 28) & ((1 << 18) - 1);
	}

	static int jobPos(long job) {
		return (int) (job >>> 19) & ((1 << 9) - 1);
	}

	static int jobOut(long job) {
		return (int) job & ((1 << 19) - 1);
	}

	static int compress(SymbolTable st, ByteStrings strings, int first, int nlines, byte[] dst, int dstOff, int size,
			int[] lenOut, int[] strOut) {
		// symbolBase is a long[] so the kernel can gather unaligned 8 byte words with two aligned long gathers
		long[] symbolBase = new long[SYMBOL_BASE_SIZE / 8 + 2];
		MemorySegment symbols = MemorySegment.ofArray(symbolBase);
		byte[] codeBase = new byte[CODE_BASE_SIZE + PAD];
		int[] shortCodes = st.shortCodesAsInts();

		long[] input = new long[BATCH + PAD]; // combined offsets of input strings (cur,end), job id and output offset
		long[] inputOrdered = new long[BATCH + PAD];
		long[] output = new long[BATCH + PAD]; // jobs with their final state
		int[] jobLine = new int[BATCH]; // for which line was this job (a line may be split into multiple jobs)
		int[] sortpos = new int[BATCH + 1];

		int curLine = 0;
		int inOff = 0;
		int outOff = 0;
		int batchPos = 0;
		int empty = 0;
		long budget = size;
		final int lim = dstOff + size;
		int d = dstOff;

		while (curLine < nlines && outOff <= CODE_BASE_SIZE) {
			int prevLine = curLine;
			int curOff = 0;

			// bail out if the output buffer cannot hold the compressed next string fully
			long lineLen = strings.length(first + curLine);
			if ((lineLen - curOff) * 2 + 7 > budget) {
				break; // see below for the +7
			} else {
				budget -= (lineLen - curOff) * 2;
			}
			strOut[curLine] = -1;
			lenOut[curLine] = 0;

			do {
				do {
					int line = first + curLine;
					int chunk = strings.length(line) - curOff;
					if (chunk > FsstEncoder.CHUNK) {
						chunk = FsstEncoder.CHUNK; // large strings need to be chopped up into segments of 511 bytes
					}
					// create a job in this batch
					long job = job(inOff, inOff + chunk, batchPos, outOff);

					// worst case estimate for compressed size (+7 is for the scatter that writes extra 7 zeros)
					outOff += 7 + 2 * chunk;
					if (outOff > CODE_BASE_SIZE) {
						break; // simdbuf may get full, stop before this chunk
					}
					// register job in this batch
					input[batchPos] = job;
					jobLine[batchPos] = curLine;

					if (chunk == 0) {
						empty++; // the vector kernel cannot handle empty strings, they need to be filtered out
					} else {
						// copy string chunk into temp buffer
						MemorySegment.copy(strings.array(line), strings.offset(line) + curOff, symbols,
								ValueLayout.JAVA_BYTE, inOff, chunk);
						inOff += chunk;
						curOff += chunk;
						// write an extra char at the end that will not be encoded
						symbols.set(ValueLayout.JAVA_BYTE, inOff++, (byte) st.terminator);
					}
					if (++batchPos == BATCH) {
						break;
					}
				} while (curOff < strings.length(first + curLine));

				if ((batchPos == BATCH) || (outOff > CODE_BASE_SIZE) || (++curLine >= nlines)
						|| ((strings.length(first + curLine) * 2L + 7) > budget)) { // cannot accumulate more?
					if (batchPos - empty >= MIN_VECTOR_JOBS) {
						// radix-sort jobs on length (longest string first)
						// -- this provides best load balancing and allows to skip empty jobs at the end
						java.util.Arrays.fill(sortpos, 0);
						for (int i = 0; i < batchPos; i++) {
							sortpos[BATCH - (jobEnd(input[i]) - jobCur(input[i]))]++;
						}
						for (int i = 1; i <= BATCH; i++) {
							sortpos[i] += sortpos[i - 1];
						}
						for (int i = 0; i < batchPos; i++) {
							int len = jobEnd(input[i]) - jobCur(input[i]);
							inputOrdered[sortpos[BATCH - 1 - len]++] = input[i];
						}
						int done = VectorKernel.compress(shortCodes, st.hashNum, st.hashIcl, codeBase, symbolBase,
								inputOrdered, output, batchPos - empty);
						for (; done < batchPos; done++) {
							output[done] = inputOrdered[done];
						}
					} else {
						System.arraycopy(input, 0, output, 0, batchPos);
					}

					// finish encoding (unfinished strings in process, plus the few last strings not yet processed)
					for (int i = 0; i < batchPos; i++) {
						long job = output[i];
						int out = jobOut(job);
						int cur = jobCur(job);
						int end = jobEnd(job);
						if (cur < end) { // finish encoding this string with scalar code
							out = finish(st, symbols, codeBase, cur, end, out);
						}
						// post-process job info: misuse the end field as compressed size
						int pos = jobPos(job);
						int startOut = jobOut(input[pos]);
						input[pos] = job(0, out - startOut, pos, startOut);
					}

					// copy out the result data
					for (int i = 0; i < batchPos; i++) {
						int lineNr = jobLine[i]; // the sort must be order-preserving, as we concatenate results
						int sz = jobEnd(input[i]); // had stored compressed lengths here
						if (strOut[lineNr] < 0) {
							strOut[lineNr] = d; // first segment will be the strOut pointer
						}
						lenOut[lineNr] += sz; // add segment (lenOut starts at 0 for this reason)
						System.arraycopy(codeBase, jobOut(input[i]), dst, d, sz);
						d += sz;
					}

					// go for the next batch of 512 chunks
					inOff = outOff = batchPos = empty = 0;
					budget = lim - d;
				}
			} while (curLine == prevLine && outOff <= CODE_BASE_SIZE);
		}
		return curLine;
	}

	private static int finish(SymbolTable st, MemorySegment symbols, byte[] codeBase, int cur, int end, int out) {
		final char[] shortCodes = st.shortCodes;
		while (cur < end) {
			long word = symbols.get(LONG_LE_UNALIGNED, cur);
			int code = shortCodes[(int) (word & 0xFFFF)];
			int idx = (int) (hash(word & 0xFFFFFF) & (HASH_TAB_SIZE - 1));
			long sIcl = st.hashIcl[idx];
			codeBase[out + 1] = (byte) word; // speculatively write out escaped byte
			word &= (-1L >>> ignoredBits(sIcl));
			if ((sIcl < ICL_FREE) && st.hashNum[idx] == word) {
				codeBase[out++] = (byte) SymbolTable.code(sIcl);
				cur += length(sIcl);
			} else {
				// could be a 2-byte or 1-byte code, or miss; handle everything with predication
				codeBase[out] = (byte) code;
				out += 1 + ((code & CODE_BASE) >> 8);
				cur += (code >> LEN_BITS);
			}
		}
		return out;
	}
}
