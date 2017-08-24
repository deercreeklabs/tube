#ifndef CONNECTION_H
#define CONNECTION_H

#include "utils.h"
#include <cstdint>
#include <string>
#include <vector>

enum State {
    CONNECTED,
    READY,
    MSG_IN_FLIGHT
};

struct Connection
{
    State state;
    int32_t peer_fragment_size;
    std::vector<std::string> fragment_buffer;
    uint32_t num_fragments;
    bool is_cur_msg_compressed;

    Connection() {
        state = CONNECTED;
        is_cur_msg_compressed = false;
    }
    void addFragment(std::string fragment) { fragment_buffer.push_back(fragment); }
    std::string getAllFragments() {
        std::string result;
        for (auto const& fragment : fragment_buffer) { result += fragment; }
        return result;
    }
    void setNumFragments(const char* data, uint32_t *num_bytes_consumed) {
        uint32_t n = data[0] & 0x07;
        if(n) {
            num_fragments = n;
        } else {

            n = decode_int(data + 1, num_bytes_consumed);
            *num_bytes_consumed += 1; // count header byte
        }
    }
};


#endif // CONNECTION_H
