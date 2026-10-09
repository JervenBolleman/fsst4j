package swiss.sib.swissprot.fsst4j;

import static jdk.incubator.vector.VectorOperators.GE;
import static jdk.incubator.vector.VectorOperators.I2L;
import static jdk.incubator.vector.VectorOperators.L2I;
import static jdk.incubator.vector.VectorOperators.LSHL;
import static jdk.incubator.vector.VectorOperators.LSHR;
import static jdk.incubator.vector.VectorOperators.XOR;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * Port of fsst_compressAVX512() (fsst_avx512.cpp and fsst_avx512_unroll1.inc) to the jdk.incubator.vector API.
 *
 * Each lane of a LongVector holds a job: the compression state of one string chunk. In each iteration one code is
 * found for every lane. Lanes whose string is done are written out (compress store) and refilled from the job input
 * (expand load). The C++ code uses 8 lanes of AVX512 registers, here the preferred species of the platform is used
 * (e.g. 4 lanes with AVX2). The lane assignment does not influence the output, which is byte for byte identical to
 * the scalar kernel.
 *
 * Differences with the C++ kernel:
 * <ul>
 * <li>java has no gather of unaligned 8 byte words from a byte array. The input buffer is therefore a long[] and each
 * word is assembled from two aligned long gathers.</li>
 * <li>Only the code and (speculative) escape byte are written, 2 bytes per lane instead of 8. There is no byte
 * scatter, so these are written lane by lane.</li>
 * <li>A lane is done when cur &gt;= end instead of cur == end, which avoids running past the end on malformed
 * tables.</li>
 * </ul>
 */
final class VectorKernel {
	private static final VectorSpecies<Long> L = LongVector.SPECIES_PREFERRED;
	private static final int VL = L.length();
	private static final VectorSpecies<Integer> I = VL >= 2
			? VectorSpecies.of(int.class, VectorShape.forBitSize(VL * Integer.SIZE))
			: null;

	private static final long M19 = (1L << 19) - 1;
	private static final long M18 = (1L << 18) - 1;

	private VectorKernel() {
	}

	static boolean isUsable() {
		return VL >= 4 && VL <= 8;
	}

	/**
	 * @param input  n jobs (sorted longest first, none empty), followed by padding
	 * @param output receives the finished jobs, then the unfinished jobs
	 * @return the number of jobs taken from the input (finished + unfinished)
	 */
	static int compress(int[] shortCodes, long[] hashNum, long[] hashIcl, byte[] codeBase, long[] symbolBase,
			long[] input, long[] output, int n) {
		final int[] idx = new int[VL];
		final long[] writes = new long[VL];
		final LongVector allMask = LongVector.broadcast(L, -1L);

		int in = 0;
		int out = 0;
		LongVector job = LongVector.zero(L);
		VectorMask<Long> loadmask = L.maskAll(true); // lanes to be loaded with new jobs (initially all)
		int delta = VL; // number of new loads this iteration

		while (in + delta < n) {
			// load new jobs in the empty lanes
			LongVector loaded = LongVector.fromArray(L, input, in);
			job = job.blend(loaded.expand(loadmask), loadmask);
			in += delta;

			// load the next 8 input string bytes: two aligned longs, shifted together
			LongVector cur = job.lanewise(LSHR, 46);
			indexes(cur.lanewise(LSHR, 3), idx);
			LongVector lo = LongVector.fromArray(L, symbolBase, 0, idx, 0);
			LongVector hi = LongVector.fromArray(L, symbolBase, 1, idx, 0);
			LongVector shift = cur.and(7).lanewise(LSHL, 3);
			LongVector word = lo.lanewise(LSHR, shift)
					.or(hi.lanewise(LSHL, 1).lanewise(LSHL, shift.neg().add(63)));

			// load 16-bits codes from the 2-byte-prefix keyed lookup table. It also stores 1-byte codes in free slots.
			// code: lowest 8 bits contain the code. Ninth bit is whether it is an escaped code. Next 4 bits is length.
			indexes(word.and(0xFFFF), idx);
			LongVector code = (LongVector) IntVector.fromArray(I, shortCodes, 0, idx, 0).convertShape(I2L, L, 0);

			// hash the first three bytes of the string: pos = pos*PRIME; pos ^= pos>>SHIFT
			LongVector pos = word.and(0xFFFFFF).mul(SymbolTable.HASH_PRIME);
			pos = pos.lanewise(XOR, pos.lanewise(LSHR, SymbolTable.SHIFT)).and(SymbolTable.HASH_TAB_SIZE - 1);
			// lookup in the 3-byte-prefix keyed hash table
			indexes(pos, idx);
			LongVector icl = LongVector.fromArray(L, hashIcl, 0, idx, 0);
			LongVector symb = LongVector.fromArray(L, hashNum, 0, idx, 0);

			// speculatively store the first input byte into the second position of write (in case it is escaped)
			LongVector write = word.and(0xFF).lanewise(LSHL, 8);
			// generate the FF..FF mask with an FF for each byte of the symbol
			LongVector symbolMask = allMask.lanewise(LSHR, icl.and(0xFF));
			// check whether it is an occupied slot and check string equality
			VectorMask<Long> match = symb.eq(word.and(symbolMask)).and(icl.lt(SymbolTable.ICL_FREE));
			// for the hits, overwrite the codes with what comes from the hash table (codes for symbols of length >=3)
			code = code.blend(icl.lanewise(LSHR, 16), match);
			// write out the code byte as the first output byte (may be the escape code 255 from shortCodes)
			write = write.or(code.and(0xFF));
			code = code.and(0xFFFF);

			// write out the compressed data, the code byte and the speculative escaped byte
			// (there is no byte scatter instruction, so the lanes are stored and written out one by one)
			indexes(job.and(M19), idx);
			write.intoArray(writes, 0);
			for (int l = 0; l < VL; l++) {
				codeBase[idx[l]] = (byte) writes[l];
				codeBase[idx[l] + 1] = (byte) (writes[l] >>> 8);
			}

			// increase job.cur with the symbol length
			job = job.add(code.lanewise(LSHR, SymbolTable.LEN_BITS).lanewise(LSHL, 46));
			// increase job.out with one, or two in case of an escape code
			job = job.add(code.lanewise(LSHR, 8).and(1).add(1));
			// test which lanes are done now (job.cur reached job.end)
			loadmask = job.lanewise(LSHR, 46).compare(GE, job.lanewise(LSHR, 28).and(M18));
			delta = loadmask.trueCount();
			// write out the job state for the lanes that are done
			job.compress(loadmask).intoArray(output, out);
			out += delta;
		}
		// flush the job states of the unfinished strings at the end of output[]
		job.compress(loadmask.not()).intoArray(output, out);
		return in;
	}

	private static void indexes(LongVector v, int[] idx) {
		v.convertShape(L2I, I, 0).reinterpretAsInts().intoArray(idx, 0);
	}
}
