package swiss.sib.swissprot.fsst4j;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Java port of the SymbolTable struct of libfsst.hpp.
 *
 * A symbol (C++ struct Symbol) is represented by two longs: num, the up to 8 symbol bytes in little endian order, and
 * icl, which packs (low-to-high) ignoredBits:16, code:12, length:4.
 */
final class SymbolTable {
	static final int LEN_BITS = 12;
	static final int CODE_BITS = 9;
	static final int CODE_BASE = 256;
	static final int CODE_MAX = 1 << CODE_BITS;
	static final int CODE_MASK = CODE_MAX - 1;
	static final int HASH_LOG2SIZE = 10;
	static final int HASH_TAB_SIZE = 1 << HASH_LOG2SIZE;
	static final long HASH_PRIME = 2971215073L;
	static final int SHIFT = 15;
	/** high bits of icl (len=15, code=CODE_MASK) mark a free hash table bucket */
	static final long ICL_FREE = (15L << 28) | ((long) CODE_MASK << 16);
	static final long FSST_VERSION = 20190218L;
	static final int MAX_SYMBOL_LENGTH = 8;

	static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
	static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

	/** code for 2-byte symbols, otherwise the code for the first byte (u16 in C++) */
	final char[] shortCodes = new char[65536];
	/** code for every 1-byte symbol, only used during construction (u16 in C++) */
	final char[] byteCodes = new char[256];
	final long[] symbolNum = new long[CODE_MAX];
	final long[] symbolIcl = new long[CODE_MAX];
	/** replicates the symbols of length 3 and more */
	final long[] hashNum = new long[HASH_TAB_SIZE];
	final long[] hashIcl = new long[HASH_TAB_SIZE];
	int nSymbols;
	int suffixLim = CODE_MAX;
	int terminator;
	boolean zeroTerminated;
	/** lenHisto[x] is the number of symbols of byte-length x+1 */
	final int[] lenHisto = new int[CODE_BITS];

	SymbolTable() {
		for (int i = 0; i < 256; i++) {
			symbolNum[i] = i;
			symbolIcl[i] = singleCharIcl(i | (1 << LEN_BITS)); // pseudo symbols
		}
		for (int i = 256; i < CODE_MAX; i++) {
			symbolNum[i] = 0;
			symbolIcl[i] = singleCharIcl(CODE_MASK); // unused
		}
		Arrays.fill(hashIcl, ICL_FREE);
		for (int i = 0; i < 256; i++) {
			byteCodes[i] = (char) ((1 << LEN_BITS) | i);
		}
		for (int i = 0; i < 65536; i++) {
			shortCodes[i] = (char) ((1 << LEN_BITS) | (i & 255));
		}
	}

	void copyFrom(SymbolTable o) {
		System.arraycopy(o.shortCodes, 0, shortCodes, 0, shortCodes.length);
		System.arraycopy(o.byteCodes, 0, byteCodes, 0, byteCodes.length);
		System.arraycopy(o.symbolNum, 0, symbolNum, 0, symbolNum.length);
		System.arraycopy(o.symbolIcl, 0, symbolIcl, 0, symbolIcl.length);
		System.arraycopy(o.hashNum, 0, hashNum, 0, hashNum.length);
		System.arraycopy(o.hashIcl, 0, hashIcl, 0, hashIcl.length);
		System.arraycopy(o.lenHisto, 0, lenHisto, 0, lenHisto.length);
		nSymbols = o.nSymbols;
		suffixLim = o.suffixLim;
		terminator = o.terminator;
		zeroTerminated = o.zeroTerminated;
	}

	private volatile int[] shortCodesInts;

	/** shortCodes widened to int, for the vector kernel which gathers from an int[] */
	int[] shortCodesAsInts() {
		int[] ints = shortCodesInts;
		if (ints == null) {
			ints = new int[shortCodes.length];
			for (int i = 0; i < ints.length; i++) {
				ints[i] = shortCodes[i];
			}
			shortCodesInts = ints;
		}
		return ints;
	}

	// ---- Symbol helpers

	/** C++ Symbol(u8 c, u16 code) */
	static long singleCharIcl(int code) {
		return (1L << 28) | ((long) code << 16) | 56;
	}

	/** C++ Symbol::set_code_len */
	static long icl(int code, int len) {
		return ((long) len << 28) | ((long) code << 16) | ((8 - len) * 8);
	}

	static int length(long icl) {
		return (int) (icl >>> 28);
	}

	static int code(long icl) {
		return (int) (icl >>> 16) & CODE_MASK;
	}

	static int ignoredBits(long icl) {
		return (int) icl & 0xFF;
	}

	static long hash(long w) {
		long m = w * HASH_PRIME;
		return m ^ (m >>> SHIFT);
	}

	static long symbolHash(long num) {
		return hash(num & 0xFFFFFF);
	}

	/** bytes [off, off+min(len,8)) as a little endian number */
	static long load(byte[] a, int off, int len) {
		if (len >= 8) {
			return (long) LONG_LE.get(a, off);
		}
		long v = 0;
		for (int i = len - 1; i >= 0; i--) {
			v = (v << 8) | (a[off + i] & 0xFF);
		}
		return v;
	}

	static long load8(byte[] a, int off) {
		return (long) LONG_LE.get(a, off);
	}

	// ---- SymbolTable methods

	void clear() {
		Arrays.fill(lenHisto, 0);
		for (int i = CODE_BASE; i < CODE_BASE + nSymbols; i++) {
			int len = length(symbolIcl[i]);
			if (len == 1) {
				int val = (int) (symbolNum[i] & 0xFF);
				byteCodes[val] = (char) ((1 << LEN_BITS) | val);
			} else if (len == 2) {
				int val = (int) (symbolNum[i] & 0xFFFF);
				shortCodes[val] = (char) ((1 << LEN_BITS) | (val & 255));
			} else {
				int idx = (int) (symbolHash(symbolNum[i]) & (HASH_TAB_SIZE - 1));
				hashNum[idx] = 0;
				hashIcl[idx] = ICL_FREE;
			}
		}
		nSymbols = 0;
	}

	boolean hashInsert(long num, long icl) {
		int idx = (int) (symbolHash(num) & (HASH_TAB_SIZE - 1));
		if (hashIcl[idx] < ICL_FREE) {
			return false; // collision in hash table
		}
		hashIcl[idx] = icl;
		hashNum[idx] = num & (-1L >>> ignoredBits(icl));
		return true;
	}

	boolean add(long num, long icl) {
		assert CODE_BASE + nSymbols < CODE_MAX;
		int len = length(icl);
		icl = icl(CODE_BASE + nSymbols, len);
		if (len == 1) {
			byteCodes[(int) (num & 0xFF)] = (char) (CODE_BASE + nSymbols + (1 << LEN_BITS));
		} else if (len == 2) {
			shortCodes[(int) (num & 0xFFFF)] = (char) (CODE_BASE + nSymbols + (2 << LEN_BITS));
		} else if (!hashInsert(num, icl)) {
			return false;
		}
		symbolNum[CODE_BASE + nSymbols] = num;
		symbolIcl[CODE_BASE + nSymbols++] = icl;
		lenHisto[len - 1]++;
		return true;
	}

	/** find the longest symbol matching the start of a[cur..end), return its code */
	int findLongestSymbol(byte[] a, int cur, int end) {
		int len = Math.min(end - cur, MAX_SYMBOL_LENGTH);
		long num = load(a, cur, len);
		long icl = icl(CODE_MAX, len);
		int idx = (int) (symbolHash(num) & (HASH_TAB_SIZE - 1));
		long hIcl = hashIcl[idx];
		if (hIcl <= icl && hashNum[idx] == (num & (-1L >>> ignoredBits(hIcl)))) {
			return (int) (hIcl >>> 16) & CODE_MASK; // matched a long symbol
		}
		if (len >= 2) {
			int code = shortCodes[(int) (num & 0xFFFF)] & CODE_MASK;
			if (code >= CODE_BASE) {
				return code;
			}
		}
		return byteCodes[(int) (num & 0xFF)] & CODE_MASK;
	}

	/**
	 * Renumber the codes so they are grouped by length (2,3,4,5,6,7,8, then 1) and fold the single byte codes into
	 * shortCodes, see the long comment above SymbolTable::finalize in libfsst.hpp. The arithmetic is done with the
	 * same (u8) wrap around as the C++.
	 */
	void finalize(boolean zeroTerminatedMode) {
		int zt = zeroTerminatedMode ? 1 : 0;
		assert nSymbols <= 255;
		int[] newCode = new int[256];
		int[] rsum = new int[8];
		int byteLim = (nSymbols - (lenHisto[0] - zt)) & 0xFF;

		rsum[0] = byteLim; // 1-byte codes are highest
		rsum[1] = zt;
		for (int i = 1; i < 7; i++) {
			rsum[i + 1] = (rsum[i] + lenHisto[i]) & 0xFF;
		}

		suffixLim = rsum[1];
		newCode[0] = 0;
		symbolNum[0] = symbolNum[CODE_BASE]; // keep symbol 0 in place (for zeroTerminated cases only)
		symbolIcl[0] = symbolIcl[CODE_BASE];

		for (int i = zt, j = rsum[2]; i < nSymbols; i++) {
			long num1 = symbolNum[CODE_BASE + i];
			int len = length(symbolIcl[CODE_BASE + i]);
			int opt = (len == 2) ? nSymbols : 0;
			if (opt != 0) {
				int first2 = (int) (num1 & 0xFFFF);
				for (int k = 0; k < opt; k++) {
					if (k != i && length(symbolIcl[CODE_BASE + k]) > 1
							&& first2 == (int) (symbolNum[CODE_BASE + k] & 0xFFFF)) {
						opt = 0; // symbol k is a suffix of s
					}
				}
				// symbols without a larger suffix have a code < suffixLim
				newCode[i] = (opt != 0 ? suffixLim++ : --j) & 0xFF;
			} else {
				newCode[i] = rsum[len - 1];
				rsum[len - 1] = (rsum[len - 1] + 1) & 0xFF;
			}
			symbolNum[newCode[i]] = num1;
			symbolIcl[newCode[i]] = icl(newCode[i], len);
		}
		suffixLim &= 0xFFFF;
		for (int i = 0; i < 256; i++) {
			if ((byteCodes[i] & CODE_MASK) >= CODE_BASE) {
				byteCodes[i] = (char) (newCode[byteCodes[i] & 0xFF] + (1 << LEN_BITS));
			} else {
				byteCodes[i] = (char) (511 + (1 << LEN_BITS));
			}
		}
		for (int i = 0; i < 65536; i++) {
			int sc = shortCodes[i];
			if ((sc & CODE_MASK) >= CODE_BASE) {
				shortCodes[i] = (char) (newCode[sc & 0xFF] + (sc & (15 << LEN_BITS)));
			} else {
				shortCodes[i] = byteCodes[i & 0xFF];
			}
		}
		for (int i = 0; i < HASH_TAB_SIZE; i++) {
			if (hashIcl[i] < ICL_FREE) {
				int c = newCode[code(hashIcl[i]) & 0xFF];
				hashNum[i] = symbolNum[c];
				hashIcl[i] = symbolIcl[c];
			}
		}
	}

	/** C++ fsst_export */
	byte[] export() {
		long version = (FSST_VERSION << 32) | ((long) suffixLim << 24) | ((long) terminator << 16)
				| ((long) nSymbols << 8) | 1; // least significant byte is nonzero (endian marker)
		int zt = zeroTerminated ? 1 : 0;
		byte[] buf = new byte[17 + 8 * 255];
		LONG_LE.set(buf, 0, version);
		buf[8] = (byte) zt;
		for (int i = 0; i < 8; i++) {
			buf[9 + i] = (byte) lenHisto[i];
		}
		int pos = 17;
		for (int i = zt; i < nSymbols; i++) {
			int len = length(symbolIcl[i]);
			long num = symbolNum[i];
			for (int j = 0; j < len; j++) {
				buf[pos++] = (byte) (num >>> (8 * j));
			}
		}
		return Arrays.copyOf(buf, pos);
	}
}
