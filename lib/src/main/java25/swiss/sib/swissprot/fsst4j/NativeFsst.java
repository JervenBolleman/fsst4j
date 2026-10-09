package swiss.sib.swissprot.fsst4j;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;

/**
 * Bindings to the C++ libfsst using the java Foreign Function and Memory API.
 *
 * The library is looked for in this order:
 * <ol>
 * <li>the file named by the system property {@code fsst4j.library}, if it exists. It may also name a directory
 * containing the library.</li>
 * <li>the precompiled library in the jar, at /native/&lt;os&gt;-&lt;arch&gt;/&lt;library&gt;, see {@link #platform()} and
 * {@link #libraryFileName()}, e.g. /native/linux-amd64/libfsst.so</li>
 * <li>libfsst on the java.library.path</li>
 * </ol>
 * Use {@link #isAvailable()} to check if the native library could be loaded. Needs
 * {@code --enable-native-access=ALL-UNNAMED} (set in the manifest of the jar) to avoid warnings.
 */
public final class NativeFsst {
	private static final Linker LINKER = Linker.nativeLinker();
	/** size_t, the bindings assume a 64 bit platform */
	private static final java.lang.foreign.ValueLayout.OfLong SIZE_T = JAVA_LONG;

	private static final SymbolLookup LOOKUP;
	private static final Throwable LOAD_ERROR;
	private static final MethodHandle FSST_CREATE;
	private static final MethodHandle FSST_DUPLICATE;
	private static final MethodHandle FSST_EXPORT;
	private static final MethodHandle FSST_IMPORT;
	private static final MethodHandle FSST_DESTROY;
	private static final MethodHandle FSST_COMPRESS;
	/** from the fsst4j shim, may be null if the library was built without it */
	private static final MethodHandle FSST4J_DECOMPRESS;
	private static final long DECODER_SIZE;
	private static final MethodHandle STRLEN = LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow("strlen"),
			FunctionDescriptor.of(SIZE_T, ADDRESS));

	static {
		SymbolLookup lookup = null;
		Throwable error = null;
		try {
			if (ADDRESS.byteSize() != 8) {
				throw new UnsupportedOperationException("only 64 bit platforms are supported");
			}
			lookup = loadLibrary();
		} catch (Throwable e) {
			error = e;
		}
		LOOKUP = lookup;
		LOAD_ERROR = error;
		if (lookup != null) {
			FSST_CREATE = handle("fsst_create", FunctionDescriptor.of(ADDRESS, SIZE_T, ADDRESS, ADDRESS, JAVA_INT));
			FSST_DUPLICATE = handle("fsst_duplicate", FunctionDescriptor.of(ADDRESS, ADDRESS));
			FSST_EXPORT = handle("fsst_export", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
			FSST_IMPORT = handle("fsst_import", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
			FSST_DESTROY = handle("fsst_destroy", FunctionDescriptor.ofVoid(ADDRESS));
			FSST_COMPRESS = handle("fsst_compress", FunctionDescriptor.of(SIZE_T, ADDRESS, SIZE_T, ADDRESS, ADDRESS,
					SIZE_T, ADDRESS, ADDRESS, ADDRESS));
			Optional<MemorySegment> decompress = lookup.find("fsst4j_decompress");
			FSST4J_DECOMPRESS = decompress.map(s -> LINKER.downcallHandle(s,
					FunctionDescriptor.of(SIZE_T, ADDRESS, SIZE_T, ADDRESS, SIZE_T, ADDRESS))).orElse(null);
			long decoderSize = 8 + 1 + 255 + 7 + 8 * 255; // sizeof(fsst_decoder_t) on 64 bit platforms
			Optional<MemorySegment> sizeFunction = lookup.find("fsst4j_decoder_size");
			if (sizeFunction.isPresent()) {
				try {
					decoderSize = (long) LINKER.downcallHandle(sizeFunction.get(), FunctionDescriptor.of(SIZE_T))
							.invokeExact();
				} catch (Throwable e) {
					throw new ExceptionInInitializerError(e);
				}
			}
			DECODER_SIZE = decoderSize;
		} else {
			FSST_CREATE = FSST_DUPLICATE = FSST_EXPORT = FSST_IMPORT = FSST_DESTROY = FSST_COMPRESS = null;
			FSST4J_DECOMPRESS = null;
			DECODER_SIZE = 0;
		}
	}

	private NativeFsst() {
	}

	private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
		return LINKER.downcallHandle(LOOKUP.findOrThrow(name), descriptor);
	}

	private static SymbolLookup loadLibrary() throws IOException {
		String configured = System.getProperty("fsst4j.library");
		if (configured != null) {
			Path path = Path.of(configured);
			if (Files.isDirectory(path)) {
				path = path.resolve(libraryFileName());
			}
			if (Files.isRegularFile(path)) {
				return SymbolLookup.libraryLookup(path, Arena.global());
			}
		}
		String library = libraryFileName();
		String resource = "/native/" + platform() + '/' + library;
		try (InputStream in = NativeFsst.class.getResourceAsStream(resource)) {
			if (in != null) {
				Path tmp = Files.createTempFile("fsst4j-", '-' + library);
				tmp.toFile().deleteOnExit();
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
				return SymbolLookup.libraryLookup(tmp, Arena.global());
			}
		}
		System.loadLibrary("fsst");
		return SymbolLookup.loaderLookup();
	}

	/**
	 * @return the platform directory of the bundled library: os-arch, with os one of linux, macos, windows and arch
	 *         the normalized os.arch (amd64, aarch64, ...). The same names are used by scripts/build-native.sh.
	 */
	public static String platform() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		if (os.startsWith("linux")) {
			os = "linux";
		} else if (os.startsWith("mac") || os.startsWith("darwin")) {
			os = "macos";
		} else if (os.startsWith("windows")) {
			os = "windows";
		} else {
			os = os.replaceAll("[^a-z0-9]", "");
		}
		String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		switch (arch) {
		case "x86_64", "x64", "amd64" -> arch = "amd64";
		case "arm64", "aarch64" -> arch = "aarch64";
		default -> arch = arch.replaceAll("[^a-z0-9]", "");
		}
		return os + '-' + arch;
	}

	/** @return the file name of the native library on this platform */
	public static String libraryFileName() {
		return System.mapLibraryName("fsst");
	}

	/** @return true if the native library was loaded */
	public static boolean isAvailable() {
		return LOOKUP != null;
	}

	/** @return why the native library could not be loaded, or null */
	public static Throwable loadError() {
		return LOAD_ERROR;
	}

	/** @return true if the library has the fsst4j shim, needed for native decompression */
	public static boolean hasNativeDecompress() {
		return FSST4J_DECOMPRESS != null;
	}

	private static void checkAvailable() {
		if (LOOKUP == null) {
			throw new UnsupportedOperationException("the native fsst library is not available", LOAD_ERROR);
		}
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) {
			return r;
		} else if (t instanceof Error e) {
			throw e;
		}
		return new IllegalStateException(t);
	}

	/**
	 * Copy the strings into native memory, one after the other.
	 *
	 * @return {lenIn, strIn} the size_t[] lengths and unsigned char*[] start pointers expected by libfsst
	 */
	private static MemorySegment[] toNative(Arena arena, ByteStrings strings) {
		int n = strings.size();
		MemorySegment data = arena.allocate(Math.max(1, strings.totalLength()));
		MemorySegment lenIn = arena.allocate(SIZE_T, Math.max(1, n));
		MemorySegment strIn = arena.allocate(ADDRESS, Math.max(1, n));
		long pos = 0;
		for (int i = 0; i < n; i++) {
			int len = strings.length(i);
			MemorySegment.copy(strings.array(i), strings.offset(i), data, JAVA_BYTE, pos, len);
			lenIn.setAtIndex(SIZE_T, i, len);
			strIn.setAtIndex(ADDRESS, i, data.asSlice(pos, len));
			pos += len;
		}
		return new MemorySegment[] { lenIn, strIn };
	}

	/**
	 * An encoder of the C++ library (fsst_encoder_t). It owns native memory (~900KB) and must be closed. Like the
	 * C++ encoder it is not thread safe, use {@link #duplicate()} to get an encoder for another thread.
	 */
	public static final class Encoder implements AutoCloseable {
		private MemorySegment encoder;
		private final boolean zeroTerminated;

		private Encoder(MemorySegment encoder, boolean zeroTerminated) {
			this.encoder = encoder;
			this.zeroTerminated = zeroTerminated;
		}

		/** Build a symbol table from a sample (fsst_create). */
		public static Encoder create(ByteStrings sample, boolean zeroTerminated) {
			checkAvailable();
			if (zeroTerminated) {
				sample.checkZeroTerminated();
			}
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment[] in = toNative(arena, sample);
				return create(sample.size(), in[0], in[1], zeroTerminated);
			}
		}

		/**
		 * Build a symbol table from strings in native memory (fsst_create).
		 *
		 * @param n     the number of strings
		 * @param lenIn size_t[n] with the byte length of each string
		 * @param strIn unsigned char*[n] with the start of each string
		 */
		public static Encoder create(long n, MemorySegment lenIn, MemorySegment strIn, boolean zeroTerminated) {
			checkAvailable();
			try {
				MemorySegment e = (MemorySegment) FSST_CREATE.invokeExact(n, lenIn, strIn, zeroTerminated ? 1 : 0);
				return new Encoder(e, zeroTerminated);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		/**
		 * Build a symbol table for zero terminated C strings in native memory. The length of each string is
		 * determined with strlen, the terminating 0 byte is counted as part of the string, as libfsst expects.
		 */
		public static Encoder createForCStrings(MemorySegment... cStrings) {
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment[] in = cStringsToNative(arena, cStrings);
				return create(cStrings.length, in[0], in[1], true);
			}
		}

		private MemorySegment handle() {
			if (encoder == null) {
				throw new IllegalStateException("encoder is closed");
			}
			return encoder;
		}

		public boolean isZeroTerminated() {
			return zeroTerminated;
		}

		/** @return a new encoder sharing the symbol table, for use in another thread (fsst_duplicate) */
		public Encoder duplicate() {
			try {
				return new Encoder((MemorySegment) FSST_DUPLICATE.invokeExact(handle()), zeroTerminated);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		/** @return the serialized symbol table (fsst_export) */
		public byte[] exportTable() {
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment buf = arena.allocate(FsstDecoder.MAX_HEADER);
				int len = (int) FSST_EXPORT.invokeExact(handle(), buf);
				return buf.asSlice(0, len).toArray(JAVA_BYTE);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		/** Compress all strings (fsst_compress), growing the output buffer as needed. */
		public FsstCompressedData compress(ByteStrings strings) {
			if (zeroTerminated) {
				strings.checkZeroTerminated();
			}
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment[] in = toNative(arena, strings);
				return compress(arena, strings.size(), in[0], in[1], strings.totalLength());
			}
		}

		/** Compress zero terminated C strings in native memory, see {@link #createForCStrings(MemorySegment...)} */
		public FsstCompressedData compressCStrings(MemorySegment... cStrings) {
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment[] in = cStringsToNative(arena, cStrings);
				long total = 0;
				for (int i = 0; i < cStrings.length; i++) {
					total += in[0].getAtIndex(SIZE_T, i);
				}
				return compress(arena, cStrings.length, in[0], in[1], total);
			}
		}

		private FsstCompressedData compress(Arena arena, int n, MemorySegment lenIn, MemorySegment strIn,
				long totalLength) {
			MemorySegment lenOut = arena.allocate(SIZE_T, Math.max(1, n));
			MemorySegment strOut = arena.allocate(ADDRESS, Math.max(1, n));
			// conservative space: 7+2*inputlength is enough for everything to fit in one go
			long outSize = 7 + 2 * totalLength;
			MemorySegment output = arena.allocate(outSize);
			int[] compressedLengths = new int[n];
			byte[] compressed = new byte[(int) Math.min(Integer.MAX_VALUE - 16, outSize)];
			int compressedSize = 0;
			int done = 0;
			while (done < n) {
				long compressedCount = compress(n - done, lenIn.asSlice(done * SIZE_T.byteSize()),
						strIn.asSlice(done * ADDRESS.byteSize()), output, lenOut, strOut);
				if (compressedCount == 0) {
					throw new IllegalStateException("could not compress string " + done);
				}
				long batchSize = 0;
				for (int i = 0; i < compressedCount; i++) {
					int len = (int) lenOut.getAtIndex(SIZE_T, i);
					compressedLengths[done + i] = len;
					batchSize += len;
				}
				// the compressed strings are consecutive in output
				MemorySegment.copy(output, JAVA_BYTE, 0, compressed, compressedSize, (int) batchSize);
				compressedSize += (int) batchSize;
				done += (int) compressedCount;
			}
			return new FsstCompressedData(compressedLengths, java.util.Arrays.copyOf(compressed, compressedSize),
					exportTable());
		}

		/**
		 * The raw fsst_compress call.
		 *
		 * @return the number of strings that were compressed (that fit in output)
		 */
		public long compress(long n, MemorySegment lenIn, MemorySegment strIn, MemorySegment output,
				MemorySegment lenOut, MemorySegment strOut) {
			try {
				return (long) FSST_COMPRESS.invokeExact(handle(), n, lenIn, strIn, output.byteSize(), output, lenOut,
						strOut);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		/** frees the native memory (fsst_destroy) */
		@Override
		public void close() {
			if (encoder != null) {
				try {
					FSST_DESTROY.invokeExact(encoder);
				} catch (Throwable t) {
					throw rethrow(t);
				} finally {
					encoder = null;
				}
			}
		}
	}

	private static MemorySegment[] cStringsToNative(Arena arena, MemorySegment[] cStrings) {
		checkAvailable();
		int n = cStrings.length;
		MemorySegment lenIn = arena.allocate(SIZE_T, Math.max(1, n));
		MemorySegment strIn = arena.allocate(ADDRESS, Math.max(1, n));
		for (int i = 0; i < n; i++) {
			if (!cStrings[i].isNative()) {
				throw new IllegalArgumentException("C strings must be in native memory");
			}
			lenIn.setAtIndex(SIZE_T, i, strlen(cStrings[i]) + 1); // the zero byte is part of the string
			strIn.setAtIndex(ADDRESS, i, cStrings[i]);
		}
		return new MemorySegment[] { lenIn, strIn };
	}

	/** @return the length of a zero terminated C string, not counting the zero */
	public static long strlen(MemorySegment cString) {
		try {
			return (long) STRLEN.invokeExact(cString);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** A C++ fsst_decoder_t in native memory, imported from a serialized symbol table. */
	public static final class Decoder implements AutoCloseable {
		private final Arena arena;
		private final MemorySegment decoder;
		private final boolean zeroTerminated;

		private Decoder(byte[] table, int offset) {
			checkAvailable();
			if (FSST4J_DECOMPRESS == null) {
				throw new UnsupportedOperationException("the native library lacks fsst4j_decompress");
			}
			arena = Arena.ofShared();
			try {
				decoder = arena.allocate(DECODER_SIZE, 8);
				MemorySegment buf = arena.allocate(FsstDecoder.MAX_HEADER);
				MemorySegment.copy(table, offset, buf, JAVA_BYTE, 0,
						Math.min(table.length - offset, FsstDecoder.MAX_HEADER));
				int read = (int) FSST_IMPORT.invokeExact(decoder, buf);
				if (read == 0) {
					throw new IllegalArgumentException("not a FSST symbol table");
				}
				zeroTerminated = (decoder.get(JAVA_BYTE, 8) & 1) != 0;
			} catch (Throwable t) {
				arena.close();
				throw rethrow(t);
			}
		}

		/** fsst_import */
		public static Decoder importTable(byte[] table, int offset) {
			return new Decoder(table, offset);
		}

		public boolean isZeroTerminated() {
			return zeroTerminated;
		}

		/** the raw fsst_decompress call, returns the full size of the decompressed string */
		public long decompress(MemorySegment in, long lenIn, MemorySegment out, long size) {
			try {
				return (long) FSST4J_DECOMPRESS.invokeExact(decoder, lenIn, in, size, out);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		/**
		 * Decompress in[offset, offset+length) into out[outOffset, outOffset+size).
		 *
		 * @return the full size of the decompressed string, which may be larger than size
		 */
		public int decompress(byte[] in, int offset, int length, byte[] out, int outOffset, int size) {
			try (Arena local = Arena.ofConfined()) {
				MemorySegment nativeIn = local.allocate(Math.max(1, length));
				MemorySegment.copy(in, offset, nativeIn, JAVA_BYTE, 0, length);
				MemorySegment nativeOut = local.allocate(Math.max(1, size));
				long full = decompress(nativeIn, length, nativeOut, size);
				MemorySegment.copy(nativeOut, JAVA_BYTE, 0, out, outOffset, (int) Math.min(full, size));
				return (int) full;
			}
		}

		@Override
		public void close() {
			arena.close();
		}
	}
}
