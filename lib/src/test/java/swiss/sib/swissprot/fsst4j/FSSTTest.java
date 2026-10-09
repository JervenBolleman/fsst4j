package swiss.sib.swissprot.fsst4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;
import swiss.sib.swissprot.fsst4j.FSST.Implementation;

public class FSSTTest {

	private static List<String> input(int size) {
		List<String> input = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			input.add("lallalaa" + i);
		}
		return input;
	}

	@Test
	public void compressBasic() {
		int size = 1_000;
		List<String> input = input(size);
		FsstCompressedData compress = FSST.compress(input);
		assertNotNull(compress);
		assertNotNull(compress.compressedData());
		assertNotNull(compress.compressedLengths());
		assertNotNull(compress.encoderSerialized());
		List<String> decoded = compress.decodeAsStrings();
		assertNotNull(decoded);
		for (int i = 0; i < size; i++) {
			assertEquals(input.get(i), decoded.get(i));
		}
	}

	@ParameterizedTest
	@EnumSource(Implementation.class)
	public void allImplementationsBothModes(Implementation implementation) {
		assumeTrue(implementation.isAvailable(), implementation + " not available");
		List<String> input = input(10_000);
		input.add("");
		input.add("non ascii: é ü 日本語 😀");
		FsstCompressedData knownLength = FSST.compress(input, false, implementation);
		FsstCompressedData zeroTerminated = FSST.compress(input, true, implementation);
		assertEquals(input, knownLength.decodeAsStrings());
		assertEquals(input, zeroTerminated.decodeAsStrings());
		// zero terminated strings decompress including their terminator, and the compressed form ends with 0 too
		assertEquals(0, zeroTerminated.decode().get(0)[input.get(0).length()]);
		int[] offsets = zeroTerminated.offsets();
		for (int i = 0; i < input.size(); i++) {
			assertEquals(0, zeroTerminated.compressedData()[offsets[i] + zeroTerminated.compressedLengths()[i] - 1]);
		}
		assertEquals(FSST.compress(input, false, Implementation.JAVA), knownLength);
	}
}
