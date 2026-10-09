package swiss.sib.swissprot.fsst4j;

import static swiss.sib.swissprot.fsst4j.SymbolTable.CODE_BASE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.CODE_MASK;
import static swiss.sib.swissprot.fsst4j.SymbolTable.CODE_MAX;
import static swiss.sib.swissprot.fsst4j.SymbolTable.HASH_TAB_SIZE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.ICL_FREE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.MAX_SYMBOL_LENGTH;
import static swiss.sib.swissprot.fsst4j.SymbolTable.hash;
import static swiss.sib.swissprot.fsst4j.SymbolTable.icl;
import static swiss.sib.swissprot.fsst4j.SymbolTable.ignoredBits;
import static swiss.sib.swissprot.fsst4j.SymbolTable.length;
import static swiss.sib.swissprot.fsst4j.SymbolTable.load8;

import java.util.Arrays;

/**
 * Java port of buildSymbolTable() and makeSample() of libfsst.cpp. Given the same input it produces the same symbol
 * table as the C++ code (on x86, where char is signed, see {@link #startsWithTerminator}).
 */
final class SymbolTableBuilder {
	static final int SAMPLETARGET = 1 << 14;
	static final int SAMPLEMAXSZ = 2 * SAMPLETARGET;
	static final int SAMPLELINE = 512;

	/** The sample to build the symbol table from: lines of possibly different arrays. */
	private static final class Sample {
		final byte[][] arrays;
		final int[] offsets;
		final int[] lengths;

		Sample(byte[][] arrays, int[] offsets, int[] lengths) {
			this.arrays = arrays;
			this.offsets = offsets;
			this.lengths = lengths;
		}

		int size() {
			return lengths.length;
		}
	}

	private final Counters counters = new Counters();
	private final Sample sample;
	private final boolean zeroTerminated;
	private int sampleFrac = 128;

	private SymbolTableBuilder(Sample sample, boolean zeroTerminated) {
		this.sample = sample;
		this.zeroTerminated = zeroTerminated;
	}

	static SymbolTable build(ByteStrings strings, boolean zeroTerminated) {
		return new SymbolTableBuilder(makeSample(strings), zeroTerminated).buildSymbolTable();
	}

	/**
	 * quickly select a uniformly random set of lines such that we have between [SAMPLETARGET,SAMPLEMAXSZ) string
	 * bytes
	 */
	private static Sample makeSample(ByteStrings in) {
		int nlines = in.size();
		long totSize = in.totalLength();
		if (totSize < SAMPLETARGET) {
			byte[][] arrays = new byte[nlines][];
			int[] offsets = new int[nlines];
			int[] lengths = new int[nlines];
			for (int i = 0; i < nlines; i++) {
				arrays[i] = in.array(i);
				offsets[i] = in.offset(i);
				lengths[i] = in.length(i);
			}
			return new Sample(arrays, offsets, lengths);
		}
		byte[] sampleBuf = new byte[SAMPLEMAXSZ];
		int maxLines = nlines + SAMPLEMAXSZ / SAMPLELINE;
		int[] offsets = new int[maxLines];
		int[] lengths = new int[maxLines];
		int n = 0;
		int pos = 0;
		long sampleRnd = hash(4637947);
		while (pos < SAMPLETARGET && n < maxLines) {
			// choose a non-empty line
			sampleRnd = hash(sampleRnd);
			int linenr = (int) Long.remainderUnsigned(sampleRnd, nlines);
			while (in.length(linenr) == 0) {
				if (++linenr == nlines) {
					linenr = 0;
				}
			}
			// choose a chunk
			int lineLen = in.length(linenr);
			long chunks = 1 + ((lineLen - 1) / SAMPLELINE);
			sampleRnd = hash(sampleRnd);
			int chunk = (int) (SAMPLELINE * Long.remainderUnsigned(sampleRnd, chunks));

			// add the chunk to the sample
			int len = Math.min(lineLen - chunk, SAMPLELINE);
			System.arraycopy(in.array(linenr), in.offset(linenr) + chunk, sampleBuf, pos, len);
			offsets[n] = pos;
			lengths[n++] = len;
			pos += len;
		}
		byte[][] arrays = new byte[n][];
		Arrays.fill(arrays, sampleBuf);
		return new Sample(arrays, Arrays.copyOf(offsets, n), Arrays.copyOf(lengths, n));
	}

	private SymbolTable buildSymbolTable() {
		SymbolTable st = new SymbolTable();
		SymbolTable bestTable = new SymbolTable();
		int bestGain = -SAMPLEMAXSZ; // worst case (everything exception)

		// start by determining the terminator. We use the (lowest) most infrequent byte as terminator
		st.zeroTerminated = zeroTerminated;
		if (zeroTerminated) {
			st.terminator = 0; // except in case of zeroTerminated mode, then byte 0 is terminator regardless frequency
		} else {
			int[] byteHisto = new int[256];
			for (int i = 0; i < sample.size(); i++) {
				byte[] a = sample.arrays[i];
				for (int j = sample.offsets[i], end = j + sample.lengths[i]; j < end; j++) {
					byteHisto[a[j] & 0xFF]++;
				}
			}
			int minSize = SAMPLEMAXSZ;
			int i = st.terminator = 256;
			while (i-- > 0) {
				int count = byteHisto[i] & 0xFFFF; // u16 in C++
				if (count > minSize) {
					continue;
				}
				st.terminator = i;
				minSize = count;
			}
		}
		assert st.terminator != 256;

		byte[] bestCounters = new byte[2 * CODE_MAX];
		for (sampleFrac = 8; true; sampleFrac += 30) {
			counters.clear();
			int gain = compressCount(st);
			if (gain >= bestGain) { // a new best solution!
				counters.backup1(bestCounters);
				bestTable.copyFrom(st);
				bestGain = gain;
			}
			if (sampleFrac >= 128) {
				break; // we do 5 rounds (sampleFrac=8,38,68,98,128)
			}
			makeTable(st);
		}
		counters.restore1(bestCounters);
		makeTable(bestTable);
		bestTable.finalize(zeroTerminated); // renumber codes for more efficient compression
		return bestTable;
	}

	/** a random number between 0 and 128 */
	private int rnd128(long i) {
		return 1 + (int) (hash((i + 1) * sampleFrac) & 127);
	}

	private static int isEscapeCode(int code) {
		return code < CODE_BASE ? 1 : 0;
	}

	/** compress sample, and compute (pair-)frequencies. Returns the gain. */
	private int compressCount(SymbolTable st) {
		int gain = 0;
		for (int i = 0; i < sample.size(); i++) {
			byte[] a = sample.arrays[i];
			int cur = sample.offsets[i];
			int start = cur;
			int end = cur + sample.lengths[i];

			if (sampleFrac < 128) {
				// in earlier rounds (sampleFrac < 128) we skip data in the sample (reduces overall work ~2x)
				if (rnd128(i) > sampleFrac) {
					continue;
				}
			}
			if (cur < end) {
				int code2;
				int code1 = st.findLongestSymbol(a, cur, end);
				cur += length(st.symbolIcl[code1]);
				gain += length(st.symbolIcl[code1]) - (1 + isEscapeCode(code1));
				while (true) {
					// count single symbol (i.e. an option is not extending it)
					counters.count1Inc(code1);

					// as an alternative, consider just using the next byte..
					if (length(st.symbolIcl[code1]) != 1) { // .. but do not count single byte symbols doubly
						counters.count1Inc(a[start] & 0xFF);
					}
					if (cur == end) {
						break;
					}

					// now match a new symbol
					start = cur;
					if (cur < end - 7) {
						long word = load8(a, cur);
						long code = word & 0xFFFFFF;
						int idx = (int) (hash(code) & (HASH_TAB_SIZE - 1));
						long sIcl = st.hashIcl[idx];
						code2 = st.shortCodes[(int) (word & 0xFFFF)] & CODE_MASK;
						word &= (-1L >>> ignoredBits(sIcl));
						if ((sIcl < ICL_FREE) & (st.hashNum[idx] == word)) {
							code2 = SymbolTable.code(sIcl);
							cur += length(sIcl);
						} else if (code2 >= CODE_BASE) {
							cur += 2;
						} else {
							code2 = st.byteCodes[(int) (word & 0xFF)] & CODE_MASK;
							cur += 1;
						}
					} else {
						code2 = st.findLongestSymbol(a, cur, end);
						cur += length(st.symbolIcl[code2]);
					}

					// compute compressed output size
					gain += (cur - start) - (1 + isEscapeCode(code2));

					if (sampleFrac < 128) { // no need to count pairs in final round
						// consider the symbol that is the concatenation of the two last symbols
						counters.count2Inc(code1, code2);

						// as an alternative, consider just extending with the next byte..
						if ((cur - start) > 1) { // ..but do not count single byte extensions doubly
							counters.count2Inc(code1, a[start] & 0xFF);
						}
					}
					code1 = code2;
				}
			}
		}
		return gain;
	}

	/**
	 * C++ compares Symbol.val.str[0] (a char, signed on x86) with the u16 terminator. A terminator >= 128 therefore
	 * never matches. We mimic x86 to produce identical symbol tables.
	 */
	private static boolean startsWithTerminator(long num, int terminator) {
		return ((byte) num) == terminator;
	}

	private void makeTable(SymbolTable st) {
		// hashmap of candidates (needed because we can generate duplicate candidates)
		Candidates cands = new Candidates();

		// artificially make terminator the most frequent symbol so it gets included
		int terminator = st.nSymbols != 0 ? CODE_BASE : st.terminator;
		counters.count1Set(terminator, 65535);

		int[] pos = new int[1];
		// add candidate symbols based on counted frequency
		for (pos[0] = 0; pos[0] < CODE_BASE + st.nSymbols; pos[0]++) {
			int cnt1 = counters.count1GetNext(pos); // may advance pos1!!
			if (cnt1 == 0) {
				continue;
			}
			int pos1 = pos[0];

			// heuristic: promoting single-byte symbols (*8) helps reduce exception rates and increases
			// [de]compression speed
			long num1 = st.symbolNum[pos1];
			int len1 = length(st.symbolIcl[pos1]);
			addOrInc(cands, num1, len1, ((len1 == 1) ? 8L : 1L) * cnt1);

			if (sampleFrac >= 128 || // last round we do not create new (combined) symbols
					len1 == MAX_SYMBOL_LENGTH || // symbol cannot be extended
					startsWithTerminator(num1, st.terminator)) { // multi-byte symbols cannot contain the terminator
				continue;
			}
			int[] pos2 = new int[1];
			for (pos2[0] = 0; pos2[0] < CODE_BASE + st.nSymbols; pos2[0]++) {
				int cnt2 = counters.count2GetNext(pos1, pos2); // may advance pos2!!
				if (cnt2 == 0) {
					continue;
				}

				// create a new symbol
				long num2 = st.symbolNum[pos2[0]];
				int len2 = length(st.symbolIcl[pos2[0]]);
				int len3 = Math.min(len1 + len2, MAX_SYMBOL_LENGTH);
				long num3 = (num2 << (8 * len1)) | num1;
				if (!startsWithTerminator(num2, st.terminator)) { // multi-byte symbols cannot contain the terminator
					addOrInc(cands, num3, len3, cnt2);
				}
			}
		}

		// order candidates by gain, ties broken by the smallest symbol number (C++ uses a priority queue)
		int n = cands.size;
		Integer[] order = new Integer[n];
		int[] slots = cands.usedSlots();
		for (int i = 0; i < n; i++) {
			order[i] = slots[i];
		}
		Arrays.sort(order, (a, b) -> {
			int c = Long.compare(cands.gain[b], cands.gain[a]);
			if (c != 0) {
				return c;
			}
			return Long.compareUnsigned(cands.num[a], cands.num[b]);
		});

		// Create new symbol map using best candidates
		st.clear();
		for (int i = 0; i < n && st.nSymbols < 255; i++) {
			int slot = order[i];
			st.add(cands.num[slot], icl(CODE_MASK, cands.len[slot]));
		}
	}

	private void addOrInc(Candidates cands, long num, int len, long count) {
		if (count < (5L * sampleFrac) / 128) {
			return; // improves both compression speed (less candidates), but also quality!!
		}
		long gain = (count * len) & 0xFFFFFFFFL; // u32 in C++
		cands.addGain(num, len, gain);
	}

	/** Open addressing hash map from (symbol num, length) to gain. */
	private static final class Candidates {
		long[] num = new long[1024];
		int[] len = new int[1024];
		long[] gain = new long[1024];
		int size;

		void addGain(long n, int l, long g) {
			if (size * 2 >= len.length) {
				grow();
			}
			int mask = len.length - 1;
			int slot = (int) (mix(n, l) & mask);
			while (len[slot] != 0) {
				if (num[slot] == n && len[slot] == l) {
					gain[slot] = (gain[slot] + g) & 0xFFFFFFFFL;
					return;
				}
				slot = (slot + 1) & mask;
			}
			num[slot] = n;
			len[slot] = l;
			gain[slot] = g;
			size++;
		}

		int[] usedSlots() {
			int[] slots = new int[size];
			int j = 0;
			for (int i = 0; i < len.length; i++) {
				if (len[i] != 0) {
					slots[j++] = i;
				}
			}
			return slots;
		}

		private static long mix(long n, int l) {
			long h = (n ^ l) * 0xc6a4a7935bd1e995L;
			return h ^ (h >>> 29);
		}

		private void grow() {
			long[] oldNum = num;
			int[] oldLen = len;
			long[] oldGain = gain;
			num = new long[oldLen.length * 2];
			len = new int[oldLen.length * 2];
			gain = new long[oldLen.length * 2];
			size = 0;
			for (int i = 0; i < oldLen.length; i++) {
				if (oldLen[i] != 0) {
					addGain(oldNum[i], oldLen[i], oldGain[i]);
				}
			}
		}
	}

	/**
	 * Port of the (non NONOPT_FSST) Counters struct. The memory layout is emulated byte for byte, as the C++ code
	 * lets 12 bit pair counters overflow into their neighbours and reads 8 bytes at a time past the end of rows.
	 */
	static final class Counters {
		private static final int COUNT1_HIGH = 0;
		private static final int COUNT1_LOW = COUNT1_HIGH + CODE_MAX;
		private static final int COUNT2_HIGH = COUNT1_LOW + CODE_MAX;
		private static final int COUNT2_LOW = COUNT2_HIGH + CODE_MAX * (CODE_MAX / 2);
		private static final int SIZE = COUNT2_LOW + CODE_MAX * CODE_MAX;
		private final byte[] mem = new byte[SIZE + 8];

		void clear() {
			Arrays.fill(mem, (byte) 0);
		}

		void count1Set(int pos1, int val) {
			mem[COUNT1_LOW + pos1] = (byte) val;
			mem[COUNT1_HIGH + pos1] = (byte) (val >> 8);
		}

		void count1Inc(int pos1) {
			// increment high early (when low==0, not when low==255). This means (high > 0) <=> (cnt > 0)
			if (mem[COUNT1_LOW + pos1]++ == 0) {
				mem[COUNT1_HIGH + pos1]++;
			}
		}

		void count2Inc(int pos1, int pos2) {
			int low = COUNT2_LOW + pos1 * CODE_MAX + pos2;
			if (mem[low]++ == 0) {
				// inc 4-bits high counter with 1<<0 (1) or 1<<4 (16) -- depending on whether pos2 is even or odd
				mem[COUNT2_HIGH + pos1 * (CODE_MAX / 2) + (pos2 >> 1)] += (byte) (1 << ((pos2 & 1) << 2));
			}
		}

		/** note: advances pos1[0] to the next nonzero counter in register range */
		int count1GetNext(int[] pos) {
			int pos1 = pos[0];
			long high = load8(mem, COUNT1_HIGH + pos1); // reads 8 subsequent counters [pos1..pos1+7]
			int zero = high != 0 ? (Long.numberOfTrailingZeros(high) >> 3) : 7; // number of zero bytes
			high = (high >>> (zero << 3)) & 255; // advance to nonzero counter
			pos[0] = pos1 += zero;
			if (pos1 >= CODE_MAX || high == 0) {
				return 0; // all zero
			}
			int low = mem[COUNT1_LOW + pos1] & 0xFF;
			if (low != 0) {
				high--; // high is incremented early and low late, so decrement high (unless low==0)
			}
			return (int) ((high << 8) + low);
		}

		/** note: advances pos[0] to the next nonzero counter in register range */
		int count2GetNext(int pos1, int[] pos) {
			int pos2 = pos[0];
			// reads 16 subsequent counters [pos2..pos2+15]
			long high = load8(mem, COUNT2_HIGH + pos1 * (CODE_MAX / 2) + (pos2 >> 1));
			high >>>= ((pos2 & 1) << 2); // odd pos2: ignore the lowest 4 bits & we see only 15 counters

			int zero = high != 0 ? (Long.numberOfTrailingZeros(high) >> 2) : (15 - (pos2 & 1));
			high = (high >>> (zero << 2)) & 15; // advance to nonzero counter
			pos[0] = pos2 += zero;
			if (pos2 >= CODE_MAX || high == 0) {
				return 0;
			}
			int low = mem[COUNT2_LOW + pos1 * CODE_MAX + pos2] & 0xFF;
			if (low != 0) {
				high--;
			}
			return (int) ((high << 8) + low);
		}

		void backup1(byte[] buf) {
			System.arraycopy(mem, COUNT1_HIGH, buf, 0, 2 * CODE_MAX);
		}

		void restore1(byte[] buf) {
			System.arraycopy(buf, 0, mem, COUNT1_HIGH, 2 * CODE_MAX);
		}
	}
}
