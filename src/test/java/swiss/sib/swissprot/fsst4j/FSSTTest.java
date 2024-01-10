package swiss.sib.swissprot.fsst4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import swiss.sib.swissprot.fsst4j.FSST.FsstCompressedData;

public class FSSTTest {

	@Test
	public void compressBasic() {

		int size = 1_000;
		List<String> input = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			input.add("lallalaa" + i);
		}
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
}
