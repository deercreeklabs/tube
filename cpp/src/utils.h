// Compression & decompression functions are
//    Copyright 2007 Timo Bingmann <tb@panthema.net>
//    Distributed under the Boost Software License, Version 1.0.
//    (See http://www.boost.org/LICENSE_1_0.txt)
// Remainder is Copyright 2017 Deer Creek Labs, LLC
//    Licensed under Apache Software License 2.0

#ifndef UTILS_H
#define UTILS_H

#include "server_wss.hpp"

#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <zlib.h>

class TubeServer;

enum CompressionType {
    NONE,
    SMART,
    DEFLATE
};

typedef SimpleWeb::SocketServer<SimpleWeb::WSS> WssServer;

typedef std::string conn_id_t;
typedef void on_rcv_fn_t(TubeServer& ts, conn_id_t conn_id,
                         const std::string& data);
typedef void on_connect_fn_t(TubeServer& ts, conn_id_t conn_id,
                             const std::string path,
                              const std::string remote_address);
typedef void on_disconnect_fn_t(TubeServer& ts, conn_id_t conn_id, int code,
                                std::string reason);

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

int32_t decode_int(const std::string& in, uint32_t *num_bytes_consumed) {
    uint32_t encoded = 0;
    uint8_t shift = 0;
    uint8_t u;
    *num_bytes_consumed = 0;
    do {
        if (shift >= 32) {
            throw std::invalid_argument("Invalid Avro varint");
        }
        u = in[(*num_bytes_consumed)++];
        encoded |= static_cast<uint32_t>(u & 0x7f) << shift;
        shift += 7;
    } while (u & 0x80);
    return static_cast<int32_t>(((encoded >> 1) ^
                                 -(static_cast<int64_t>(encoded) & 1)));
}

std::string compress(const std::string& str,
                     int compressionlevel = Z_BEST_COMPRESSION) {
    z_stream zs;                        // z_stream is zlib's control structure
    memset(&zs, 0, sizeof(zs));

    if (deflateInit(&zs, compressionlevel) != Z_OK)
        throw(std::runtime_error("deflateInit failed while compressing."));

    zs.next_in = (Bytef*)str.data();
    zs.avail_in = str.size();           // set the z_stream's input

    int ret;
    char outbuffer[32768];
    std::string outstring;

    // retrieve the compressed bytes blockwise
    do {
        zs.next_out = reinterpret_cast<Bytef*>(outbuffer);
        zs.avail_out = sizeof(outbuffer);

        ret = deflate(&zs, Z_FINISH);

        if (outstring.size() < zs.total_out) {
            // append the block to the output string
            outstring.append(outbuffer,
                             zs.total_out - outstring.size());
        }
    } while (ret == Z_OK);

    deflateEnd(&zs);

    if (ret != Z_STREAM_END) {          // an error occurred that was not EOF
        std::ostringstream oss;
        oss << "Exception during zlib compression: (" << ret << ") " << zs.msg;
        throw(std::runtime_error(oss.str()));
    }

    return outstring;
}

std::string decompress(const std::string& str) {
    z_stream zs;                        // z_stream is zlib's control structure
    memset(&zs, 0, sizeof(zs));

    if (inflateInit(&zs) != Z_OK)
        throw(std::runtime_error("inflateInit failed while decompressing."));

    zs.next_in = (Bytef*)str.data();
    zs.avail_in = str.size();

    int ret;
    char outbuffer[32768];
    std::string outstring;

    // get the decompressed bytes blockwise using repeated calls to inflate
    do {
        zs.next_out = reinterpret_cast<Bytef*>(outbuffer);
        zs.avail_out = sizeof(outbuffer);

        ret = inflate(&zs, 0);

        if (outstring.size() < zs.total_out) {
            outstring.append(outbuffer,
                             zs.total_out - outstring.size());
        }

    } while (ret == Z_OK);

    inflateEnd(&zs);

    if (ret != Z_STREAM_END) {          // an error occurred that was not EOF
        std::ostringstream oss;
        oss << "Exception during zlib decompression: (" << ret << ") "
            << zs.msg;
        throw(std::runtime_error(oss.str()));
    }

    return outstring;
}

#endif // UTILS_H
