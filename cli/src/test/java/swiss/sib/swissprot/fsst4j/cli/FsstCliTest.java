package swiss.sib.swissprot.fsst4j.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import swiss.sib.swissprot.fsst4j.FSST.Implementation;

class FsstCliTest {
	@TempDir
	Path tmp;

	private Path sample() throws Exception {
		// the dbtext corpus of the FSST paper, from the fsst git submodule (see pom.xml)
		Path hamlet = Path.of(System.getProperty("fsst4j.corpus.dir", "../fsst/paper/dbtext"), "hamlet");
		if (Files.exists(hamlet)) {
			return hamlet;
		}
		Path text = tmp.resolve("text");
		Files.writeString(text, "to be or not to be, that is the question\n".repeat(2000));
		return text;
	}

	@Test
	void compressDecompress() throws Exception {
		Path in = sample();
		Path compressed = tmp.resolve("c.fsst");
		Path out = tmp.resolve("out");
		assertEquals(0, FsstCli.run("compress", "-q", in.toString(), compressed.toString()));
		assertEquals(0, FsstCli.run("decompress", "-q", compressed.toString(), out.toString()));
		assertArrayEquals(Files.readAllBytes(in), Files.readAllBytes(out));
		if (Implementation.NATIVE.isAvailable()) {
			Path nativeCompressed = tmp.resolve("n.fsst");
			assertEquals(0, FsstCli.run("compress", "-q", "-i", "native", in.toString(), nativeCompressed.toString()));
			assertArrayEquals(Files.readAllBytes(compressed), Files.readAllBytes(nativeCompressed));
			assertEquals(0, FsstCli.run("decompress", "-q", "--native", compressed.toString(), out.toString()));
			assertArrayEquals(Files.readAllBytes(in), Files.readAllBytes(out));
		}
	}

	@Test
	void lines() throws Exception {
		Path in = sample();
		PrintStream stdout = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setOut(new PrintStream(captured, true));
		try {
			assertEquals(0, FsstCli.run("lines", in.toString()));
			assertEquals(0, FsstCli.run("lines", "-z", "-i", "java", in.toString()));
		} finally {
			System.setOut(stdout);
		}
		String output = captured.toString();
		assertNotEquals(-1, output.indexOf("JAVA"), output);
	}

	@Test
	void unavailableImplementationIsAnError() throws Exception {
		assumeTrue(!Implementation.NATIVE.isAvailable());
		assertNotEquals(0, FsstCli.run("compress", "-i", "native", sample().toString(), tmp.resolve("x").toString()));
	}
}
