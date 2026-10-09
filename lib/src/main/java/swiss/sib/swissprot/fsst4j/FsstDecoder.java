package swiss.sib.swissprot.fsst4j;

import static swiss.sib.swissprot.fsst4j.SymbolTable.INT_LE;
import static swiss.sib.swissprot.fsst4j.SymbolTable.LONG_LE;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A pure java FSST decompressor, a port of fsst_import() and fsst_decompress() (C++ fsst_decoder_t). It is immutable
 * and can be shared between threads.
 */
public final class FsstDecoder {
	/** A compressed string is a string of 1-byte codes; except for code 255, which is followed by an uncompressed byte. */
	public static final int ESC = 255;
	/** maximum length of a serialized symbol table, as produced by fsst_export() */
	public static final int MAX_HEADER = 8 + 1 + 8 + 2048 + 1;
	/** 7-byte number in little endian containing "corrupt", used for unused codes */
	private static final long CORRUPT = 32774747032022883L;

	private final boolean zeroTerminated;
	/** len[x] is the byte-length of the symbol x */
	private final byte[] len = new byte[255];
	/** symbol[x] contains in little endian the byte sequence that code x represents */
	private final long[] symbol = new long[255];
	private final int headerLength;

	private FsstDecoder(byte[] buf, int off) {
		long version = (long) LONG_LE.get(buf, off);
		if ((version >>> 32) != SymbolTable.FSST_VERSION) {
			throw new IllegalArgumentException("not a FSST symbol table, version " + (version >>> 32));
		}
		zeroTerminated = (buf[off + 8] & 1) != 0;
		int[] lenHisto = new int[8];
		for (int i = 0; i < 8; i++) {
			lenHisto[i] = buf[off + 9 + i] & 0xFF;
		}
		// in case of zero-terminated, first symbol is "" (zero always, may be overwritten)
		len[0] = 1;
		symbol[0] = 0;

		// we use lenHisto[0] as 1-byte symbol run length (at the end)
		int code = zeroTerminated ? 1 : 0;
		if (zeroTerminated) {
			lenHisto[0] = (lenHisto[0] - 1) & 0xFF; // symbol "" aka 1-byte code=0, is not stored at the end
		}
		int pos = off + 17;
		for (int l = 1; l <= 8; l++) { // l = 1,2,3,4,5,6,7,8
			for (int i = 0; i < lenHisto[l & 7]; i++, code++) { // l&7 = 1,2,3,4,5,6,7,0
				if (code >= 255) {
					throw new IllegalArgumentException("corrupt FSST symbol table, too many symbols");
				}
				int symLen = (l & 7) + 1; // len = 2,3,4,5,6,7,8,1
				len[code] = (byte) symLen;
				long s = 0;
				for (int j = 0; j < symLen; j++) {
					s |= (buf[pos++] & 0xFFL) << (8 * j);
				}
				symbol[code] = s;
			}
		}
		// fill unused symbols with text "corrupt". Gives a chance to detect corrupted code sequences
		while (code < 255) {
			symbol[code] = CORRUPT;
			len[code++] = 8;
		}
		headerLength = pos - off;
	}

	/** Read a symbol table serialized by fsst_export() or {@link FsstEncoder#exportTable()} */
	public static FsstDecoder importTable(byte[] buf, int offset) {
		Objects.checkFromIndexSize(offset, 17, buf.length);
		return new FsstDecoder(buf, offset);
	}

	/** @return the number of bytes the serialized symbol table occupied */
	public int headerLength() {
		return headerLength;
	}

	public boolean isZeroTerminated() {
		return zeroTerminated;
	}

	/** @return the length of the decompressed string, without decompressing it */
	public int decompressedLength(byte[] in, int offset, int length) {
		int out = 0;
		for (int i = offset, end = offset + length; i < end; i++) {
			int code = in[i] & 0xFF;
			if (code < ESC) {
				out += len[code];
			} else {
				i++;
				out++;
			}
		}
		return out;
	}

	public byte[] decompress(byte[] in) {
		return decompress(in, 0, in.length);
	}

	public byte[] decompress(byte[] in, int offset, int length) {
		byte[] out = new byte[decompressedLength(in, offset, length)];
		decompress(in, offset, length, out, 0, out.length);
		return out;
	}

	/**
	 * Decompress to UTF-8 text. For zero terminated strings the terminating 0 byte is not included.
	 */
	public String decompressToString(byte[] in, int offset, int length) {
		byte[] out = decompress(in, offset, length);
		int l = out.length;
		if (zeroTerminated && l > 0 && out[l - 1] == 0) {
			l--;
		}
		return new String(out, 0, l, StandardCharsets.UTF_8);
	}

	/**
	 * Decompress a single string, port of fsst_decompress().
	 *
	 * @param size the room in out. If the decompressed string is longer, the output is truncated (and for zero
	 *             terminated strings the last byte written is set to 0).
	 * @return the size of the decompressed string, which can be larger than size
	 */
	public int decompress(byte[] strIn, int inOff, int lenIn, byte[] strOut, int outOff, int size) {
		Objects.checkFromIndexSize(inOff, lenIn, strIn.length);
		Objects.checkFromIndexSize(outOff, size, strOut.length);
		final byte[] len = this.len;
		final long[] symbol = this.symbol;
		int code;
		int posOut = 0;
		int posIn = 0;
		while (posOut + 32 <= size && posIn + 4 <= lenIn) {
			int nextBlock = (int) INT_LE.get(strIn, inOff + posIn);
			int escapeMask = (nextBlock & 0x80808080) & ((((~nextBlock) & 0x7F7F7F7F) + 0x7F7F7F7F) ^ 0x80808080);
			if (escapeMask == 0) {
				code = strIn[inOff + posIn++] & 0xFF;
				LONG_LE.set(strOut, outOff + posOut, symbol[code]);
				posOut += len[code];
				code = strIn[inOff + posIn++] & 0xFF;
				LONG_LE.set(strOut, outOff + posOut, symbol[code]);
				posOut += len[code];
				code = strIn[inOff + posIn++] & 0xFF;
				LONG_LE.set(strOut, outOff + posOut, symbol[code]);
				posOut += len[code];
				code = strIn[inOff + posIn++] & 0xFF;
				LONG_LE.set(strOut, outOff + posOut, symbol[code]);
				posOut += len[code];
			} else {
				int firstEscapePos = Integer.numberOfTrailingZeros(escapeMask) >> 3;
				// the C++ uses a Duff's device: decode the codes before the escape, then the escaped byte
				for (int i = 0; i < firstEscapePos; i++) {
					code = strIn[inOff + posIn++] & 0xFF;
					LONG_LE.set(strOut, outOff + posOut, symbol[code]);
					posOut += len[code];
				}
				posIn += 2;
				strOut[outOff + posOut++] = strIn[inOff + posIn - 1]; // decompress an escaped byte
			}
		}
		if (posOut + 32 <= size) { // handle the possibly 3 last bytes without a loop
			if (posIn + 2 <= lenIn) {
				strOut[outOff + posOut] = strIn[inOff + posIn + 1];
				if ((strIn[inOff + posIn] & 0xFF) != ESC) {
					code = strIn[inOff + posIn++] & 0xFF;
					LONG_LE.set(strOut, outOff + posOut, symbol[code]);
					posOut += len[code];
					if ((strIn[inOff + posIn] & 0xFF) != ESC) {
						code = strIn[inOff + posIn++] & 0xFF;
						LONG_LE.set(strOut, outOff + posOut, symbol[code]);
						posOut += len[code];
					} else {
						posIn += 2;
						strOut[outOff + posOut++] = strIn[inOff + posIn - 1];
					}
				} else {
					posIn += 2;
					posOut++;
				}
			}
			if (posIn < lenIn) { // last code cannot be an escape
				code = strIn[inOff + posIn++] & 0xFF;
				LONG_LE.set(strOut, outOff + posOut, symbol[code]);
				posOut += len[code];
			}
		}
		while (posIn < lenIn) {
			if ((code = strIn[inOff + posIn++] & 0xFF) < ESC) {
				int posWrite = posOut;
				int endWrite = posOut + len[code];
				long s = symbol[code];
				if ((posOut = endWrite) > size) {
					endWrite = size;
				}
				for (; posWrite < endWrite; posWrite++) { // only write if there is room
					strOut[outOff + posWrite] = (byte) (s >>> (8 * (posWrite + len[code] - posOut)));
				}
			} else {
				if (posOut < size) {
					strOut[outOff + posOut] = strIn[inOff + posIn]; // idem
				}
				posIn++;
				posOut++;
			}
		}
		if (posOut >= size && zeroTerminated && size > 0) {
			strOut[outOff + size - 1] = 0;
		}
		return posOut; // full size of decompressed string (could be >size, then the actually decompressed part)
	}

	/** @return a copy of the symbol of code (for debugging and tests) */
	byte[] symbol(int code) {
		byte[] s = new byte[len[code]];
		for (int i = 0; i < s.length; i++) {
			s[i] = (byte) (symbol[code] >>> (8 * i));
		}
		return s;
	}

	@Override
	public String toString() {
		return "FsstDecoder[zeroTerminated=" + zeroTerminated + ", symbols=" + Arrays.toString(len) + "]";
	}
}
