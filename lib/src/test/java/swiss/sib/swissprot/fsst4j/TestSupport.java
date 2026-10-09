package swiss.sib.swissprot.fsst4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Locations of the upstream test corpus and the natively built tools, see pom.xml and scripts/build-native.sh */
public final class TestSupport {
	private TestSupport() {
	}

	/** the dbtext corpus of the FSST paper, from the fsst git submodule */
	public static Path corpusDir() {
		return Path.of(System.getProperty("fsst4j.corpus.dir", "fsst/paper/dbtext"));
	}

	public static Stream<Path> corpusFiles() {
		Path dir = corpusDir();
		if (!Files.isDirectory(dir)) {
			return Stream.empty();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(Files::isRegularFile).sorted().toList().stream();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** the upstream fsst command line tool, built by scripts/build-native.sh */
	public static Path fsstTool() {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		return Path.of(System.getProperty("fsst4j.native.dir", "target/native"), windows ? "fsst.exe" : "fsst");
	}
}
