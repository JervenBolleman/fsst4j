package swiss.sib.swissprot.fsst4j.cli;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import swiss.sib.swissprot.fsst4j.ByteStrings;
import swiss.sib.swissprot.fsst4j.FSST;
import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;
import swiss.sib.swissprot.fsst4j.FSST.Implementation;
import swiss.sib.swissprot.fsst4j.FsstBlockFormat;
import swiss.sib.swissprot.fsst4j.FsstDecoder;
import swiss.sib.swissprot.fsst4j.NativeFsst;

/**
 * Command line utility. The compress and decompress commands use the same file format as the fsst tool of the C++
 * distribution.
 */
@Command(name = "fsst4j", mixinStandardHelpOptions = true, version = "fsst4j 1.0-SNAPSHOT",
		description = "FSST (Fast Static Symbol Table) string compression.",
		subcommands = { FsstCli.Compress.class, FsstCli.Decompress.class, FsstCli.Lines.class,
				FsstCli.Info.class })
public final class FsstCli {

	public static void main(String[] args) {
		System.exit(run(args));
	}

	/** run without calling System.exit, for tests */
	public static int run(String... args) {
		return new CommandLine(new FsstCli()).setCaseInsensitiveEnumValuesAllowed(true).execute(args);
	}

	static InputStream open(String file) throws IOException {
		return "-".equals(file) ? System.in : new BufferedInputStream(Files.newInputStream(Path.of(file)), 1 << 16);
	}

	static OutputStream create(String file) throws IOException {
		return "-".equals(file) ? System.out
				: new BufferedOutputStream(Files.newOutputStream(Path.of(file)), 1 << 16);
	}

	static void report(String what, FsstBlockFormat.Stats stats, boolean quiet) {
		if (!quiet) {
			long percentage = stats.in() == 0 ? 0 : (100 * stats.out()) / stats.in();
			System.err.println(what + " " + stats.in() + " bytes into " + stats.out() + " bytes ==> " + percentage
					+ "%");
		}
	}

	static void checkAvailable(Implementation implementation) {
		if (!implementation.isAvailable()) {
			String hint = switch (implementation) {
			case JAVA_VECTOR -> "run java with --add-modules jdk.incubator.vector";
			case NATIVE -> "native library could not be loaded: " + NativeFsst.loadError();
			case JAVA -> "";
			};
			throw new CommandLine.ParameterException(new CommandLine(new FsstCli()),
					implementation + " is not available, " + hint);
		}
	}

	@Command(name = "compress", description = "Compress a file in blocks, compatible with the C++ fsst tool.")
	static final class Compress implements Callable<Integer> {
		@Parameters(index = "0", description = "input file, - for stdin")
		String input;

		@Parameters(index = "1", arity = "0..1", description = "output file, - for stdout (default: <input>.fsst)")
		String output;

		@Option(names = { "-i", "--implementation" }, defaultValue = "JAVA",
				description = "compressor to use: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
		Implementation implementation;

		@Option(names = { "-q", "--quiet" }, description = "do not print statistics")
		boolean quiet;

		@Override
		public Integer call() throws IOException {
			checkAvailable(implementation);
			String out = output != null ? output : input + ".fsst";
			try (InputStream in = open(input); OutputStream os = create(out)) {
				report("Compressed", FsstBlockFormat.compress(in, os, implementation), quiet);
			}
			return 0;
		}
	}

	@Command(name = "decompress", description = "Decompress a file made by compress or the C++ fsst tool.")
	static final class Decompress implements Callable<Integer> {
		@Parameters(index = "0", description = "input file, - for stdin")
		String input;

		@Parameters(index = "1", description = "output file, - for stdout")
		String output;

		@Option(names = "--native", description = "decompress with the C++ library")
		boolean useNative;

		@Option(names = { "-q", "--quiet" }, description = "do not print statistics")
		boolean quiet;

		@Override
		public Integer call() throws IOException {
			if (useNative) {
				checkAvailable(Implementation.NATIVE);
			}
			try (InputStream in = open(input); OutputStream os = create(output)) {
				report("Decompressed", FsstBlockFormat.decompress(in, os, useNative), quiet);
			}
			return 0;
		}
	}

	@Command(name = "lines",
			description = "Compress each line of a text file as a separate string (the way FSST is meant to be used),"
					+ " verify the round trip and report compression factor and speed.")
	static final class Lines implements Callable<Integer> {
		@Parameters(arity = "1..*", description = "text files")
		List<Path> files;

		@Option(names = { "-i", "--implementation" }, split = ",",
				description = "compressors to compare: ${COMPLETION-CANDIDATES} (default: all available)")
		List<Implementation> implementations;

		@Option(names = { "-z", "--zero-terminated" },
				description = "compress the lines as zero terminated strings instead of known length strings")
		boolean zeroTerminated;

		@Option(names = { "-r", "--repeat" }, defaultValue = "1", description = "repeat to warm up the JIT")
		int repeat;

		@Override
		public Integer call() throws IOException {
			List<Implementation> impls = implementations != null ? implementations
					: Arrays.stream(Implementation.values()).filter(Implementation::isAvailable).toList();
			impls.forEach(FsstCli::checkAvailable);
			PrintStream out = System.out;
			out.println("file\timplementation\tstrings\tbytes\tcompressed\tfactor\tcompressMB/s\tdecompressMB/s");
			int failures = 0;
			for (Path file : files) {
				ByteStrings lines = ByteStrings.splitLines(Files.readAllBytes(file));
				if (zeroTerminated) {
					lines = lines.withZeroTerminators();
				}
				for (Implementation impl : impls) {
					FsstCompressedData compressed = null;
					long compressNanos = Long.MAX_VALUE;
					long decompressNanos = Long.MAX_VALUE;
					for (int r = 0; r < repeat; r++) {
						long start = System.nanoTime();
						compressed = FSST.compress(lines, zeroTerminated, impl);
						compressNanos = Math.min(compressNanos, System.nanoTime() - start);
						start = System.nanoTime();
						if (!verify(lines, compressed)) {
							failures++;
							System.err.println("round trip failed for " + file + " with " + impl);
							break;
						}
						decompressNanos = Math.min(decompressNanos, System.nanoTime() - start);
					}
					long bytes = lines.totalLength();
					long size = compressed.compressedData().length + compressed.encoderSerialized().length;
					out.printf("%s\t%s\t%d\t%d\t%d\t%.3f\t%.1f\t%.1f%n", file.getFileName(), impl, lines.size(), bytes,
							size, size == 0 ? 0.0 : (double) bytes / size, mbPerSecond(bytes, compressNanos),
							mbPerSecond(bytes, decompressNanos));
				}
			}
			return failures == 0 ? 0 : 1;
		}

		private static double mbPerSecond(long bytes, long nanos) {
			return nanos == 0 ? 0 : (bytes / 1e6) / (nanos / 1e9);
		}

		private static boolean verify(ByteStrings lines, FsstCompressedData compressed) {
			FsstDecoder decoder = compressed.decoder();
			int[] lengths = compressed.compressedLengths();
			byte[] data = compressed.compressedData();
			byte[] buffer = new byte[64];
			int pos = 0;
			for (int i = 0; i < lines.size(); i++) {
				int len = lines.length(i);
				if (buffer.length < len + 32) {
					buffer = new byte[2 * len + 32];
				}
				int decompressed = decoder.decompress(data, pos, lengths[i], buffer, 0, buffer.length);
				if (decompressed != len || !Arrays.equals(buffer, 0, len, lines.array(i), lines.offset(i),
						lines.offset(i) + len)) {
					return false;
				}
				pos += lengths[i];
			}
			return true;
		}
	}

	@Command(name = "info", description = "Show which implementations are available.")
	static final class Info implements Callable<Integer> {
		@Override
		public Integer call() {
			for (Implementation impl : Implementation.values()) {
				System.out.println(impl + "\t" + (impl.isAvailable() ? "available" : "not available"));
			}
			if (!NativeFsst.isAvailable()) {
				System.out.println("native load error: " + NativeFsst.loadError());
			}
			return 0;
		}
	}
}
