package swiss.sib.swissprot.fsst4j;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;
import swiss.sib.swissprot.fsst4j.FSST.Implementation;

/**
 * The file format of the fsst command line tool of the C++ distribution (fsst.cpp). Files written here can be
 * decompressed with the C++ tool and vice versa, the compressed files are even byte for byte identical.
 *
 * A compressed file is a sequence of blocks, each with format:
 * <ol>
 * <li>3-byte big endian block length field. This byte-length includes (1), (2) and (3).</li>
 * <li>FSST symbol table as produced by fsst_export().</li>
 * <li>the FSST compressed data, the input block compressed as one string.</li>
 * </ol>
 */
public final class FsstBlockFormat {
	private static final int MEMBUF = 1 << 22;
	/** block size of compression (max compressed size must fit 3 bytes), as in fsst.cpp */
	public static final int BLOCK_SIZE = MEMBUF - (1 + FsstDecoder.MAX_HEADER / 2);

	/** byte counts of a (de)compression run */
	public record Stats(long in, long out) {
	}

	private FsstBlockFormat() {
	}

	public static Stats compress(InputStream in, OutputStream out, Implementation implementation)
			throws IOException {
		byte[] block = new byte[BLOCK_SIZE];
		long inTotal = 0;
		long outTotal = 0;
		int read;
		while ((read = in.readNBytes(block, 0, BLOCK_SIZE)) > 0) {
			ByteStrings string = ByteStrings.of(block, new int[] { 0 }, new int[] { read });
			FsstCompressedData compressed = FSST.compress(string, false, implementation);
			byte[] header = compressed.encoderSerialized();
			byte[] data = compressed.compressedData();
			int blockLength = 3 + header.length + data.length;
			out.write(new byte[] { (byte) (blockLength >>> 16), (byte) (blockLength >>> 8), (byte) blockLength });
			out.write(header);
			out.write(data);
			inTotal += read;
			outTotal += blockLength;
		}
		return new Stats(inTotal, outTotal);
	}

	/**
	 * @param useNative decompress with the C++ fsst_decompress instead of the java decoder
	 */
	public static Stats decompress(InputStream in, OutputStream out, boolean useNative) throws IOException {
		long inTotal = 0;
		long outTotal = 0;
		byte[] lengthBytes = new byte[3];
		byte[] output = new byte[0];
		while (true) {
			int read = in.readNBytes(lengthBytes, 0, 3);
			if (read == 0) {
				break;
			} else if (read != 3) {
				throw new EOFException("truncated block length");
			}
			int blockLength = ((lengthBytes[0] & 0xFF) << 16) | ((lengthBytes[1] & 0xFF) << 8)
					| (lengthBytes[2] & 0xFF);
			if (blockLength < 3 + 17) {
				throw new IOException("corrupt block length " + blockLength);
			}
			byte[] block = in.readNBytes(blockLength - 3);
			if (block.length != blockLength - 3) {
				throw new EOFException("truncated block");
			}
			FsstDecoder decoder = FsstDecoder.importTable(block, 0);
			int hdr = decoder.headerLength();
			int size = decoder.decompressedLength(block, hdr, block.length - hdr);
			if (output.length < size) {
				output = new byte[Math.max(size, Math.min(MEMBUF, 2 * output.length))];
			}
			if (useNative) {
				try (NativeFsst.Decoder nativeDecoder = NativeFsst.Decoder.importTable(block, 0)) {
					nativeDecoder.decompress(block, hdr, block.length - hdr, output, 0, size);
				}
			} else {
				decoder.decompress(block, hdr, block.length - hdr, output, 0, size);
			}
			out.write(output, 0, size);
			inTotal += blockLength;
			outTotal += size;
		}
		return new Stats(inTotal, outTotal);
	}
}
