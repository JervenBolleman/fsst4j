# Protoypical bindings for using FSST in java

Using panama/jextract. "Working" but not at all ready for prime time. See issues for things to be done.

# Decompressor

The decompressor is in pure java. The first reason is that the decompressor object is not linkable from the dynamic library.
At this time it does not use the duff device.

# Compiling fsst code

```sh
cd ~/git/fsst
cmake -DBUILD_SHARED_LIBS:BOOL=ON .
make
```
The building of shared libs is required

# Thanks to the FSST developers

I would like to thank the [FSST](https://github.com/cwida/fsst) developers for writing this really nice code and making it open source, 
documenting and presenting it.
