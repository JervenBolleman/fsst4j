
# FSST for java

Java bindings and a pure java implementation of [FSST](https://github.com/cwida/fsst) (Fast Static Symbol Table)
string compression. FSST compresses batches of (short) strings with a shared symbol table, and every string can be
decompressed on its own.

Three compressors are available. They produce **byte for byte identical** symbol tables and compressed strings, so
data can be exchanged freely between them and with C++ programs using libfsst:

| implementation | class | notes |
| --- | --- | --- |
| `JAVA` | `FsstEncoder` | pure java port of libfsst (symbol table construction and the scalar `compressBulk` kernel) |
| `JAVA_VECTOR` | `FsstEncoder` with `Kernel.VECTOR` | port of the AVX512 `compressSIMD` kernel to the incubating `jdk.incubator.vector` API |
| `NATIVE` | `NativeFsst` | the C++ libfsst, called through the Foreign Function & Memory API (no JNI, no jextract needed) |

Decompression is pure java (`FsstDecoder`, a port of `fsst_decompress` including its 4-codes-at-a-time fast path).
The C++ decompressor can be used as well through `NativeFsst.Decoder`.

### Java versions: a multi-release jar

The jar is a multi-release jar:

* on **Java 11 and later** the pure java implementation (`JAVA`, `FsstEncoder`, `FsstDecoder`, block format and CLI) is
  available. `NATIVE` and `JAVA_VECTOR` report themselves as unavailable (`Implementation.isAvailable()` /
  `unavailableReason()`), and `NativeFsst` does not exist.
* on **Java 25 and later** the classes in `META-INF/versions/25` add the FFM bindings (`NativeFsst`) and the vector
  kernel.

Sources (in `lib`): `src/main/java` holds the shared code and must stay java 11 compatible; `src/main/java25` holds the FFM and
vector API code; the small `Accelerators` class has a java 11 version (`src/main/java11`, stubs) and a java 25 version
(`src/main/java25`, delegating). Maven compiles the shared code twice (`--release 11` into `target/classes-java11` and
`--release 25` into `target/classes`) and lays out the jar in `prepare-package`. The tests run against the java 25
classes, `Java11BaseTest` runs against the java 11 classes.

## Modules

| directory | artifact | contents | dependencies |
| --- | --- | --- | --- |
| `lib` | `swiss.sib.swissprot:fsst4j` | the library | none |
| `cli` | `swiss.sib.swissprot:fsst4j-cli` | the command line tool, a runnable jar | fsst4j, picocli |

Use the library with:

```xml
<dependency>
	<groupId>swiss.sib.swissprot</groupId>
	<artifactId>fsst4j</artifactId>
	<version>1.0-SNAPSHOT</version>
</dependency>
```

## Usage

```java
// known length strings, may contain any byte
ByteStrings strings = ByteStrings.of(listOfByteArrays);
FsstEncoder encoder = FsstEncoder.build(strings, false);
FsstCompressedData compressed = encoder.compress(strings);
byte[] table = compressed.encoderSerialized();      // fsst_export() format, store it with the data

FsstDecoder decoder = FsstDecoder.importTable(table, 0);
byte[] third = decoder.decompress(compressed.compressedData(), compressed.offsets()[2], compressed.compressedLengths()[2]);

// or with the convenience API
FsstCompressedData c = FSST.compress(List.of("http://a.org/1", "http://a.org/2"), false, Implementation.JAVA);
List<String> back = c.decodeAsStrings();
```

### Zero terminated and known length strings

FSST supports two kinds of strings, selected with the `zeroTerminated` flag:

* **known length** (`false`): strings may contain any byte, including `0`.
* **zero terminated** (`true`), C strings: the terminating `0` byte is part of the string and counted in its length,
  exactly as libfsst expects. Compressed strings then also end in a `0` byte (so they can be handled as C strings),
  and decompression returns the terminating `0`. Every non empty string must end with its only `0` byte; this is
  checked (libfsst silently corrupts strings with embedded zeros).

Helpers: `ByteStrings.ofUtf8(strings, true)` appends the terminators, `ByteStrings.splitZeroTerminated(buffer, off, len)`
splits a buffer of consecutive C strings, `withZeroTerminators()` converts known length strings. For C strings in
native memory, `NativeFsst.Encoder.createForCStrings(segments...)` and `compressCStrings(...)` determine the lengths
with `strlen`. `decodeAsStrings()` strips the terminator.

## Command line

```sh
mvn package
java -jar cli/target/fsst4j-cli-1.0-SNAPSHOT.jar compress file            # writes file.fsst
java -jar cli/target/fsst4j-cli-1.0-SNAPSHOT.jar decompress file.fsst file
java -jar cli/target/fsst4j-cli-1.0-SNAPSHOT.jar lines -r 10 file.txt      # per line compression: ratio and speed per implementation
java -jar cli/target/fsst4j-cli-1.0-SNAPSHOT.jar info                      # which implementations are available
```

The cli is compiled for java 11 and the library is multi-release, so `java -jar` works on java 11 and later, with only the pure java implementation below
java 25.

`compress`/`decompress` use the block file format of the `fsst` tool of the C++ distribution, the output files are
identical. Use `-i native|java|java_vector` to pick the compressor and `--native` to decompress with libfsst. The
vector implementation needs `java --add-modules jdk.incubator.vector -jar ...`.

### Native executable (GraalVM native-image)

```sh
mvn -Pnative-image package            # needs GraalVM 25+; -Dnative.image=/path/to/native-image if not on the PATH
cli/target/fsst4j compress file
```

This builds `cli/target/fsst4j`, a standalone executable that starts in milliseconds. It is built from the runnable cli
jar, of which native-image uses the java 25 classes of the multi-release library, and includes the FFM bindings: the bundled `libfsst.so` and the downcall signatures are registered in
`cli/src/main/native-image/ffm/reachability-metadata.json`, and native-image's experimental foreign API support is enabled.
The picocli reflection configuration is generated by the `picocli-codegen` annotation processor. The vector kernel
is not included (`JAVA_VECTOR` reports unavailable). In the native executable the pure java implementation is
faster than going through FFM (urls: 178 MB/s for `JAVA` and 109 MB/s for `NATIVE`), so `JAVA` stays the default.

For a native executable with only the pure java implementation (no experimental options), build from the java 11
classes: `native-image -cp lib/target/classes-java11:cli/target/classes:picocli-4.7.7.jar -o fsst4j
swiss.sib.swissprot.fsst4j.cli.FsstCli`.

## Performance

`lines -r 15` on the paper's dbtext corpus (AVX2 machine without AVX512, OpenJDK 27-ea, MB/s of input, includes symbol
table construction):

| file | JAVA | JAVA_VECTOR | NATIVE |
| --- | --- | --- | --- |
| urls | 211 | 135 | 190 |
| email | 150 | 93 | 107 |
| l_comment | 164 | 124 | 120 |

The pure java scalar compressor is as fast as libfsst called through FFM (which also has to copy the strings to native
memory). The vector kernel is correct but slower than scalar: without AVX512 there are only 4 lanes, and gathers and
the per lane byte writes are expensive. Upstream also only uses its SIMD kernel on AVX512 machines. So
`Kernel.AUTO` uses the scalar kernel, unless `-Dfsst4j.vector=true` is set. Note that GraalVM's JIT does not
intrinsify the Vector API (yet), making the vector kernel very slow there; use a HotSpot (C2) JVM.

## Building and testing

The upstream sources are a git submodule:

```sh
git submodule update --init
mvn test
```

When the submodule is present, `scripts/build-native.sh` is run by maven to build `libfsst.so` (with a small shim,
`lib/src/main/native/fsst4j_shim.cpp`, exporting the header-inline `fsst_decompress`) and the upstream `fsst` tool into
`lib/target/native`. The tests then:

* run the dbtext corpus of the FSST paper (`fsst/paper/dbtext`) line by line, in both string modes, through all three
  compressors and require identical symbol tables and output, and round trip with the java and C++ decompressors
  (`CorpusCompatibilityTest`)
* round trip every corpus file through the upstream `fsst` tool and the java block format in both directions and
  require byte identical compressed files (`BlockFormatInteropTest`)
* test edge cases: empty batches and strings, strings longer than the 511 byte chunks, binary data, decompression
  into too small buffers against the C++ decompressor, C strings in native memory (`EdgeCaseTest`)

Native libraries are bundled in the jar as `native/<os>-<arch>/<library>` (`linux|macos|windows` -
`amd64|aarch64`, see `NativeFsst.platform()`). The repository contains only `lib/src/main/resources/native/linux-amd64/libfsst.so`,
updated with `scripts/build-native.sh --install`; release jars contain all platforms (see below). It is built without `-march=native` so it runs on any x86-64 CPU (the AVX512
kernel is only used after a runtime check). The library can be overridden with `-Dfsst4j.library=/path/libfsst.so`.

## Continuous integration and releases

GitHub Actions workflows in `.github/workflows`:

* `native.yml` (reusable): builds libfsst and the upstream `fsst` tool for linux-amd64, linux-aarch64 (in
  manylinux_2_28 containers: glibc 2.28+, libstdc++ linked statically), macos-aarch64, macos-amd64, windows-amd64
  (MinGW-w64) and, experimentally, windows-aarch64 (clang), runs the library tests against each build and uploads the
  libraries as artifacts.
* `ci.yml`: on pushes to main and pull requests: `native.yml`, the full build with Java 25, and a check that the
  jars work on Java 11.
* `release.yml`: started from the Actions tab with a version (or by pushing a tag `v<version>`). Runs `native.yml`,
  bundles all native libraries into the library jar, builds with `-Prelease` (sources and javadoc jars) and creates a
  **draft** GitHub release with the library and cli jars. The version is set in the build only; it is not committed.

Deploying to Maven Central is prepared but off by default: tick "Deploy to Maven Central" when starting the release
workflow (or set the repository variable `DEPLOY_TO_CENTRAL` to `true` for tag pushes). It needs:

1. the `swiss.sib.swissprot` namespace verified for your account on https://central.sonatype.com
2. repository secrets `CENTRAL_USERNAME` and `CENTRAL_TOKEN` (a Central Portal user token)
3. repository secrets `GPG_PRIVATE_KEY` (`gpg --armor --export-secret-keys KEYID`) and `GPG_PASSPHRASE`, with the
   public key published on a key server

The parent pom and `fsst4j` are uploaded (the cli is not), validated, and then wait in the Central Portal to be
published by hand; set `central.autoPublish` to `true` in the parent pom's release profile to publish directly.

The build uses the Maven wrapper (`./mvnw`) so all platforms use the same Maven.

### Compatibility notes

* The java port replicates libfsst exactly, including the 12 bit pair counters that may overflow into their
  neighbours. One quirk is platform dependent: the C++ compares a `char` with the terminator byte, and `char` is
  signed on x86 but unsigned on ARM. The java port follows x86, and `scripts/build-native.sh` compiles with
  `-fsigned-char`, so the native libraries produce identical output on all platforms (checked by `EdgeCaseTest`).
* On Windows libfsst is built with MinGW-w64. Two upstream portability problems are worked around in the build script:
  `__builtin_ctzl` is used on 64 bit values (but `long` is 32 bits on Windows) and `__cpuidex` is used without
  including `intrin.h`.
* Only 64 bit, little endian platforms are supported by the native bindings and the vector kernel.
* `NativeFsst`, `FsstVectorCompressor` and `VectorKernel` only exist in `META-INF/versions/25`. `jar --validate`
  warns about such version-only public classes; they are intentionally not part of the java 11 API.

# Thanks to the FSST developers

I would like to thank the [FSST](https://github.com/cwida/fsst) developers for writing this really nice code and making it open source,
documenting and presenting it.
