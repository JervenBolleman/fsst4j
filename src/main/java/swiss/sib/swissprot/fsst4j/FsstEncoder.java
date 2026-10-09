package swiss.sib.swissprot.fsst4j;

import static swiss.sib.swissprot.fsst4j.SymbolTable.CODE_BASE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.HASH_TAB_SIZE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.ICL_FREE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.LEN_BITS;
import static swiss.sib.swissprot.fsst4j.SymbolTable.hash;
import static swiss.sib.swissprot.fsst4j.SymbolTable.ignoredBits;
import static swiss.sib.swissprot.fsst4j.SymbolTable.length;
import static swiss.sib.swissprot.fsst4j.SymbolTable.load8;

import java.util.Arrays;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;

/**
 * A pure java FSST compressor. It is a port of libfsst and produces byte for byte the same symbol tables and
 * compressed strings as the C++ library, so data can be exchanged freely with the native implementation.
 *
 * An encoder is immutable after construction and can be used from multiple threads.
 */
public final class FsstEncoder {
	/** Which compression kernel to use. All produce identical output. */
	public enum Kernel {
		/** port of the scalar compressBulk() */
		SCALAR,
		/** port of the AVX512 compressSIMD() using the jdk.incubator.vector API (java 25+) */
		VECTOR,
		/** pick one */
		AUTO
	}

	/** chunk size; longer strings are compressed in pieces of this size, for compatibility with the SIMD kernel */
	static final int CHUNK = 511;

	final SymbolTable st;
	private final boolean noSuffixOpt;
	private final boolean avoidBranch;

	private FsstEncoder(SymbolTable st) {
		this.st = st;
		// adaptive choosing of scalar compression method based on symbol length histogram (compressAuto)
		int[] lenHisto = st.lenHisto;
		boolean nso = false;
		boolean ab = false;
		if (100 * lenHisto[1] > 65 * st.nSymbols && 100 * st.suffixLim > 95 * lenHisto[1]) {
			nso = true;
		} else if ((lenHisto[0] > 24 && lenHisto[0] < 92) && (lenHisto[0] < 43 || lenHisto[6] + lenHisto[7] < 29)
				&& (lenHisto[0] < 72 || lenHisto[2] < 72)) {
			ab = true;
		}
		this.noSuffixOpt = nso;
		this.avoidBranch = ab;
	}

	/**
	 * Build a symbol table from a sample of strings (C++ fsst_create). It is best to provide at least 16KB of data.
	 *
	 * @param sample         strings to sample from
	 * @param zeroTerminated whether the strings are zero terminated. If so every (non empty) string must end with its
	 *                       only 0 byte, and the compressed strings will be zero terminated as well.
	 */
	public static FsstEncoder build(ByteStrings sample, boolean zeroTerminated) {
		if (zeroTerminated) {
			sample.checkZeroTerminated();
		}
		return new FsstEncoder(SymbolTableBuilder.build(sample, zeroTerminated));
	}

	public boolean isZeroTerminated() {
		return st.zeroTerminated;
	}

	/** @return the number of symbols in the table */
	public int symbolCount() {
		return st.nSymbols;
	}

	/** Serialize the symbol table, in the format of the C++ fsst_export(). */
	public byte[] exportTable() {
		return st.export();
	}

	public FsstDecoder decoder() {
		return FsstDecoder.importTable(exportTable(), 0);
	}

	/** the worst case compressed size of strings with the given total length */
	public static long maxCompressedSize(ByteStrings strings) {
		return 7 + 2 * strings.totalLength();
	}

	public FsstCompressedData compress(ByteStrings strings) {
		return compress(strings, Kernel.AUTO);
	}

	public FsstCompressedData compress(ByteStrings strings, Kernel kernel) {
		if (st.zeroTerminated) {
			strings.checkZeroTerminated();
		}
		long max = maxCompressedSize(strings);
		if (max > Integer.MAX_VALUE - 16) {
			throw new IllegalArgumentException("too much data for one batch, compress in smaller batches");
		}
		byte[] out = new byte[(int) max];
		int n = strings.size();
		int[] lenOut = new int[n];
		int[] offOut = new int[n];
		int done = compress(strings, 0, n, out, 0, out.length, lenOut, offOut, kernel);
		if (done != n) {
			throw new IllegalStateException("output buffer too small, compressed " + done + " of " + n);
		}
		int size = n == 0 ? 0 : offOut[n - 1] + lenOut[n - 1];
		return new FsstCompressedData(lenOut, Arrays.copyOf(out, size), exportTable());
	}

	/**
	 * Compress strings [first, first+count) into out (C++ fsst_compress). The compressed strings are written one
	 * after the other. The output must be large; at least 7+2*length for the next string to be compressed.
	 *
	 * @param lenOut receives the compressed length of each string (index 0 is string first)
	 * @param offOut receives the offset in out of each compressed string (index 0 is string first)
	 * @return the number of strings that were compressed, which is less than count if out is too small
	 */
	public int compress(ByteStrings strings, int first, int count, byte[] out, int outOff, int outLen, int[] lenOut,
			int[] offOut, Kernel kernel) {
		if (kernel == Kernel.AUTO) {
			kernel = autoKernel(strings, first, count);
		}
		if (kernel == Kernel.VECTOR) {
			return Accelerators.vectorCompress(st, strings, first, count, out, outOff, outLen, lenOut, offOut);
		}
		return compressBulk(strings, first, count, out, outOff, outLen, lenOut, offOut);
	}

	private Kernel autoKernel(ByteStrings strings, int first, int count) {
		if (!Accelerators.vectorPreferred()) {
			return Kernel.SCALAR;
		}
		// same heuristic as fsst_compress(): simd needs 64 lines or more of length >=12; or fewer, but big ones
		long totLen = 0;
		for (int i = first; i < first + count; i++) {
			totLen += strings.length(i);
		}
		boolean simd = totLen > count * 12L && (count > 64 || totLen > (1 << 15));
		return simd ? Kernel.VECTOR : Kernel.SCALAR;
	}

	/** optimized adaptive scalar compression method, port of compressBulk() */
	private int compressBulk(ByteStrings strings, int first, int count, byte[] out, int outOff, int outLen,
			int[] lenOut, int[] offOut) {
		final SymbolTable st = this.st;
		final char[] shortCodes = st.shortCodes;
		final long[] hashIcl = st.hashIcl;
		final long[] hashNum = st.hashNum;
		final int suffixLim = st.suffixLim;
		final int byteLim = (st.nSymbols + (st.zeroTerminated ? 1 : 0) - st.lenHisto[0]) & 0xFF;
		final int lim = outOff + outLen;
		// +8 sentinel is to avoid 8-byte unaligned-loads going beyond 511 out-of-bounds
		final byte[] buf = new byte[CHUNK + 1 + 8];
		int o = outOff;
		int curLine;
		for (curLine = 0; curLine < count; curLine++) {
			int line = first + curLine;
			byte[] in = strings.array(line);
			int inOff = strings.offset(line);
			int inLen = strings.length(line);
			int curOff = 0;
			offOut[curLine] = o;
			do {
				int chunk = Math.min(inLen - curOff, CHUNK);
				if ((2 * chunk + 7) > lim - o) {
					return curLine; // out of memory
				}
				// copy the string to the 511-byte buffer
				System.arraycopy(in, inOff + curOff, buf, 0, chunk);
				buf[chunk] = (byte) st.terminator;
				int cur = 0;
				int end = chunk;

				// three variants, chosen based on symbol table stats, to be nice to the branch predictor
				if (noSuffixOpt) {
					while (cur < end) {
						long word = load8(buf, cur);
						int code = shortCodes[(int) (word & 0xFFFF)];
						if ((code & 0xFF) < suffixLim) {
							// 2 byte code without having to worry about longer matches
							out[o++] = (byte) code;
							cur += 2;
						} else {
							int idx = (int) (hash(word & 0xFFFFFF) & (HASH_TAB_SIZE - 1));
							long sIcl = hashIcl[idx];
							out[o + 1] = (byte) word; // speculatively write out escaped byte
							word &= (-1L >>> ignoredBits(sIcl));
							if ((sIcl < ICL_FREE) && hashNum[idx] == word) {
								out[o++] = (byte) SymbolTable.code(sIcl);
								cur += length(sIcl);
							} else if ((code & 0xFF) < byteLim) {
								// 2 byte code after checking there is no longer pattern
								out[o++] = (byte) code;
								cur += 2;
							} else {
								// 1 byte code or miss.
								out[o] = (byte) code;
								o += 1 + ((code & CODE_BASE) >> 8);
								cur++;
							}
						}
					}
				} else if (avoidBranch) {
					while (cur < end) {
						long word = load8(buf, cur);
						int code = shortCodes[(int) (word & 0xFFFF)];
						int idx = (int) (hash(word & 0xFFFFFF) & (HASH_TAB_SIZE - 1));
						long sIcl = hashIcl[idx];
						out[o + 1] = (byte) word; // speculatively write out escaped byte
						word &= (-1L >>> ignoredBits(sIcl));
						if ((sIcl < ICL_FREE) && hashNum[idx] == word) {
							out[o++] = (byte) SymbolTable.code(sIcl);
							cur += length(sIcl);
						} else {
							// could be a 2-byte or 1-byte code, or miss; handle everything with predication
							out[o] = (byte) code;
							o += 1 + ((code & CODE_BASE) >> 8);
							cur += (code >> LEN_BITS);
						}
					}
				} else {
					while (cur < end) {
						long word = load8(buf, cur);
						int code = shortCodes[(int) (word & 0xFFFF)];
						int idx = (int) (hash(word & 0xFFFFFF) & (HASH_TAB_SIZE - 1));
						long sIcl = hashIcl[idx];
						out[o + 1] = (byte) word; // speculatively write out escaped byte
						word &= (-1L >>> ignoredBits(sIcl));
						if ((sIcl < ICL_FREE) && hashNum[idx] == word) {
							out[o++] = (byte) SymbolTable.code(sIcl);
							cur += length(sIcl);
						} else if ((code & 0xFF) < byteLim) {
							// 2 byte code after checking there is no longer pattern
							out[o++] = (byte) code;
							cur += 2;
						} else {
							// 1 byte code or miss.
							out[o] = (byte) code;
							o += 1 + ((code & CODE_BASE) >> 8);
							cur++;
						}
					}
				}
			} while ((curOff += CHUNK) < inLen);
			lenOut[curLine] = o - offOut[curLine];
		}
		return curLine;
	}
}
