package swiss.sib.swissprot.fsst4j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;
import swiss.sib.swissprot.fsst4j.FSST.Implementation;

/**
 * Runs against the java 11 base classes of the multi-release jar (target/classes-java11, see the java11-base
 * surefire execution in pom.xml): the pure java implementation must work, the FFM and vector implementations must
 * be reported as unavailable. Only uses API that exists in the java 11 base.
 */
class Java11BaseTest {
	@BeforeAll
	static void onlyOnBaseClasses() {
		assertTrue(Boolean.getBoolean("fsst4j.base.classes"), "run by the java11-base surefire execution");
	}

	@Test
	void onlyPureJavaIsAvailable() {
		assertThrows(ClassNotFoundException.class, () -> Class.forName("swiss.sib.swissprot.fsst4j.NativeFsst"));
		assertTrue(Implementation.JAVA.isAvailable());
		assertFalse(Implementation.NATIVE.isAvailable());
		assertFalse(Implementation.JAVA_VECTOR.isAvailable());
		assertTrue(Implementation.NATIVE.unavailableReason().contains("java 25"));
		List<String> strings = List.of("a", "b");
		assertThrows(UnsupportedOperationException.class, () -> FSST.compress(strings, false, Implementation.NATIVE));
		ByteStrings bytes = ByteStrings.ofUtf8(strings, false);
		assertThrows(UnsupportedOperationException.class,
				() -> FsstEncoder.build(bytes, false).compress(bytes, FsstEncoder.Kernel.VECTOR));
	}

	@Test
	void roundTripBothModes() throws Exception {
		Path hamlet = TestSupport.corpusDir().resolve("hamlet");
		List<String> lines = new ArrayList<>();
		if (Files.exists(hamlet)) {
			lines.addAll(Files.readAllLines(hamlet));
		} else {
			for (int i = 0; i < 5000; i++) {
				lines.add("to be or not to be " + i);
			}
		}
		for (boolean zeroTerminated : new boolean[] { false, true }) {
			FsstCompressedData compressed = FSST.compress(lines, zeroTerminated, Implementation.JAVA);
			assertEquals(lines, compressed.decodeAsStrings());
			// AUTO must fall back to the scalar kernel
			ByteStrings bytes = ByteStrings.ofUtf8(lines, zeroTerminated);
			assertEquals(compressed, FsstEncoder.build(bytes, zeroTerminated).compress(bytes));
		}
	}

	@Test
	void blockFormat() throws Exception {
		byte[] data = "the quick brown fox jumps over the lazy dog\n".repeat(10_000).getBytes();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		FsstBlockFormat.compress(new ByteArrayInputStream(data), compressed, Implementation.JAVA);
		ByteArrayOutputStream decompressed = new ByteArrayOutputStream();
		FsstBlockFormat.decompress(new ByteArrayInputStream(compressed.toByteArray()), decompressed, false);
		assertArrayEquals(data, decompressed.toByteArray());

	}
}
