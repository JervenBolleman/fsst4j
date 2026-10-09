// Small additions to libfsst for consumption via the Java FFM API.
//
// fsst_decompress() is an inline function in fsst.h, so it is not exported from libfsst.
// This file exports a non-inline wrapper so the native decompressor can be called from java.
#include "fsst.h"

extern "C" {

size_t fsst4j_decompress(const fsst_decoder_t *decoder, size_t lenIn, const unsigned char *strIn, size_t size,
                         unsigned char *output) {
   return fsst_decompress(decoder, lenIn, strIn, size, output);
}

size_t fsst4j_decoder_size() {
   return sizeof(fsst_decoder_t);
}
}
