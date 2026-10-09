package swiss.sib.swissprot.fsst4j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import swiss.sib.swissprot.fsst4j.FSST.Implementation;

/**
 * Round trips the dbtext corpus through the upstream fsst command line tool (fsst.cpp, built from the submodule by
 * scripts/build-native.sh) and through the java block format implementation.
 */
class BlockFormatInteropTest {
	@TempDir
	Path tmp;

	static Stream<Path> corpus() {
		return TestSupport.corpusFiles();
	}

	private static void fsst(String... args) throws IOException, InterruptedException {
		Path tool = TestSupport.fsstTool();
		assumeTrue(Files.isExecutable(tool), "upstream fsst tool not built: " + tool);
		List<String> command = new java.util.ArrayList<>();
		command.add(tool.toString());
		command.addAll(List.of(args));
		Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		assertEquals(0, p.waitFor(), "fsst " + String.join(" ", args));
	}

	private static byte[] javaCompress(byte[] data, Implementation impl) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FsstBlockFormat.compress(new ByteArrayInputStream(data), out, impl);
		return out.toByteArray();
	}

	private static byte[] javaDecompress(byte[] data, boolean useNative) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FsstBlockFormat.decompress(new ByteArrayInputStream(data), out, useNative);
		return out.toByteArray();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("corpus")
	void interoperatesWithCppTool(Path file) throws Exception {
		byte[] original = Files.readAllBytes(file);
		Path cppCompressed = tmp.resolve("cpp.fsst");
		fsst(file.toString(), cppCompressed.toString());
		byte[] cpp = Files.readAllBytes(cppCompressed);

		// the java compressor writes exactly what the C++ tool writes
		byte[] java = javaCompress(original, Implementation.JAVA);
		assertArrayEquals(cpp, java, "compressed file");
		if (Implementation.JAVA_VECTOR.isAvailable()) {
			assertArrayEquals(cpp, javaCompress(original, Implementation.JAVA_VECTOR), "vector compressed file");
		}

		// java decompresses C++ output
		assertArrayEquals(original, javaDecompress(cpp, false));
		if (NativeFsst.hasNativeDecompress()) {
			assertArrayEquals(original, javaDecompress(cpp, true));
		}

		// C++ decompresses java output
		Path javaCompressed = tmp.resolve("java.fsst");
		Files.write(javaCompressed, java);
		Path decompressed = tmp.resolve("decompressed");
		fsst("-d", javaCompressed.toString(), decompressed.toString());
		assertArrayEquals(original, Files.readAllBytes(decompressed));
	}

	/** files bigger than one block (4MB) are split in several blocks */
	@Test
	void multipleBlocks() throws Exception {
		Random random = new Random(42);
		StringBuilder text = new StringBuilder();
		String[] words = { "protein", "kinase", "uniprot", "swissprot", "sequence", "human", "mouse", "receptor" };
		while (text.length() < FsstBlockFormat.BLOCK_SIZE * 2 + 1000) {
			text.append(words[random.nextInt(words.length)]).append(random.nextInt(1000)).append(' ');
		}
		byte[] original = text.toString().getBytes();
		byte[] compressed = javaCompress(original, Implementation.JAVA);
		assertArrayEquals(original, javaDecompress(compressed, false));

		Path in = tmp.resolve("big.txt");
		Files.write(in, original);
		Path cppCompressed = tmp.resolve("big.txt.fsst");
		fsst(in.toString());
		assertArrayEquals(Files.readAllBytes(cppCompressed), compressed);
	}

	@Test
	void emptyInput() throws IOException {
		assertEquals(0, javaCompress(new byte[0], Implementation.JAVA).length);
		assertEquals(0, javaDecompress(new byte[0], false).length);
	}
}
