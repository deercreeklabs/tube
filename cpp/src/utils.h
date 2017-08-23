#ifndef UTILS_H
#define UTILS_H

#include <iostream>
#include <stdexcept>

using namespace std;

// Returns consumed buffer length
uint_fast8_t encode_int(int32_t i, char *buffer) {
    uint32_t n = (i << 1) ^ (i >> 31); // Zig zag encode
    uint8_t buf_idx = 0;
    while(true) {
        if(n & -128) {
            buffer[buf_idx++] = (n & 0x7f) | 0x80;
            n >>= 7;
        } else {
            buffer[buf_idx++] = (n & 0x7f);
            return buf_idx;
        }
    }
}

int32_t decode_int(char *buffer) {
    uint32_t encoded = 0;
    uint8_t shift = 0;
    uint8_t u;
    uint32_t buf_idx = 0;
    do {
        if (shift >= 32) {
            throw invalid_argument("Invalid Avro varint");
        }
        u = buffer[buf_idx++];
        encoded |= static_cast<uint32_t>(u & 0x7f) << shift;
        shift += 7;
    } while (u & 0x80);
    return static_cast<int32_t>(((encoded >> 1) ^
                                 -(static_cast<int64_t>(encoded) & 1)));
}

#endif // UTILS_H
