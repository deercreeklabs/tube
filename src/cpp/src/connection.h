#ifndef CONNECTION_H
#define CONNECTION_H

#include <uWS/uWS.h>  // Needs to be first for some reason

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
    conn_id_t conn_id;
    State state;
    int32_t peer_fragment_size;
    std::vector<std::string> fragment_buffer;
    int32_t num_fragments_expected;
    bool is_cur_msg_compressed;

    Connection(conn_id_t conn_id) {
        this->conn_id = conn_id;
        state = CONNECTED;
        peer_fragment_size = 0;
        num_fragments_expected = 0;
        is_cur_msg_compressed = false;
    }

    void addFragment(std::string fragment) {
        fragment_buffer.push_back(fragment);
    }

    std::string assembleFragments() {
        std::string result;
        for (auto const& fragment : fragment_buffer) { result += fragment; }
        fragment_buffer.clear();
        num_fragments_expected = 0;
        if(is_cur_msg_compressed) {
            return decompress(result);
        } else {
            return result;
        }
    }

    bool areAllFragmentsRcvd() {
        return fragment_buffer.size() == num_fragments_expected;
    }

    void setNumFragmentsExpected(const char* data,
                                 uint32_t *num_bytes_consumed) {
        int_fast8_t n = data[0] & 0x07;
        if(n) {
            num_fragments_expected = n;
            *num_bytes_consumed = 1;
        } else {
            num_fragments_expected = decode_int(data + 1, num_bytes_consumed);
            *num_bytes_consumed += 1; // count header byte
        }
    }
};


#endif // CONNECTION_H
