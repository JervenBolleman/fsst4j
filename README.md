
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

Requires Java 25 or later.

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
java -jar target/fsst4j-1.0-SNAPSHOT-cli.jar compress file            # writes file.fsst
java -jar target/fsst4j-1.0-SNAPSHOT-cli.jar decompress file.fsst file
java -jar target/fsst4j-1.0-SNAPSHOT-cli.jar lines -r 10 file.txt      # per line compression: ratio and speed per implementation
java -jar target/fsst4j-1.0-SNAPSHOT-cli.jar info                      # which implementations are available
```

`compress`/`decompress` use the block file format of the `fsst` tool of the C++ distribution, the output files are
identical. Use `-i native|java|java_vector` to pick the compressor and `--native` to decompress with libfsst. The
vector implementation needs `java --add-modules jdk.incubator.vector -jar ...`.

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
`src/main/native/fsst4j_shim.cpp`, exporting the header-inline `fsst_decompress`) and the upstream `fsst` tool into
`target/native`. The tests then:

* run the dbtext corpus of the FSST paper (`fsst/paper/dbtext`) line by line, in both string modes, through all three
  compressors and require identical symbol tables and output, and round trip with the java and C++ decompressors
  (`CorpusCompatibilityTest`)
* round trip every corpus file through the upstream `fsst` tool and the java block format in both directions and
  require byte identical compressed files (`BlockFormatInteropTest`)
* test edge cases: empty batches and strings, strings longer than the 511 byte chunks, binary data, decompression
  into too small buffers against the C++ decompressor, C strings in native memory (`EdgeCaseTest`)

The precompiled library shipped in the jar (`src/main/resources/Linux/amd64/libfsst.so`) is updated with
`scripts/build-native.sh --install`. It is built without `-march=native` so it runs on any x86-64 CPU (the AVX512
kernel is only used after a runtime check). The library can be overridden with `-Dfsst4j.library=/path/libfsst.so`.

### Compatibility notes

* The java port replicates libfsst exactly, including the 12 bit pair counters that may overflow into their
  neighbours. One quirk is platform dependent: the C++ compares a `char` with the terminator byte, and `char` is
  signed on x86 but unsigned on ARM. The java port follows x86.
* Only 64 bit, little endian platforms are supported by the native bindings and the vector kernel.

# Thanks to the FSST developers

I would like to thank the [FSST](https://github.com/cwida/fsst) developers for writing this really nice code and making it open source,
documenting and presenting it.
