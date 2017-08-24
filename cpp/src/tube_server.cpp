#include "connection.h"
#include "tube_server.h"
#include "utils.h"
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#include <utility>
#include <uWS/uWS.h>
#include <vector>

#define FRAGMENT_SIZE 32000

using namespace std;

TubeServer::TubeServer(const char *sslKey, const char *sslCert, uint32_t port,
                       CompressionType compression_type,
                       on_rcv_fn_t on_rcv_fn,
                       on_connect_fn_t on_connect_fn,
                       on_disconnect_fn_t on_disconnect_fn) {
    this->compression_type = compression_type;
    next_conn_id = 0;
    hub.onConnection(
        [this, on_connect_fn]
        (ws_t *ws, uWS::HttpRequest req) {
            conn_id_t conn_id = this->next_conn_id++;
            Connection conn(conn_id);
            this->conn_id_to_ws.insert({conn_id, ws});
            this->ws_to_conn.insert({ws, conn});
            on_connect_fn(*this, conn_id);
        });
    hub.onDisconnection(
        [this, on_disconnect_fn]
        (ws_t *ws, uint32_t code, char *message, size_t length) {
            conn_id_t conn_id = this->ws_to_conn.at(ws).conn_id;
            this->conn_id_to_ws.erase(conn_id);
            this->ws_to_conn.erase(ws);
            on_disconnect_fn(*this, conn_id, message, length);
        });
    hub.onMessage(
        [this, on_rcv_fn]
        (ws_t *ws, char *message,
         size_t length, uWS::OpCode opCode) {
            onMessage(ws, message, length, opCode, on_rcv_fn);
        });
    hub.listen(port);
}

void TubeServer::sendControlCode(ws_t *ws, uint_fast8_t code) {
    char buf = code << 3;
    ws->send(&buf, 1, uWS::OpCode::BINARY);
}

void TubeServer::onMessage(ws_t *ws, char *message,
                           size_t length, uWS::OpCode opCode,
                           on_rcv_fn_t on_rcv_fn) {
    if(opCode != uWS::OpCode::BINARY) {
        throw invalid_argument("Got non-binary message on websocket.");
    }
    Connection& conn = this->ws_to_conn.at(ws);
    switch (conn.state) {
    case CONNECTED: // fragment size request
    {
        uint32_t num_bytes_consumed;
        conn.peer_fragment_size = decode_int(message,
                                             &num_bytes_consumed);
        char buf[4] = {0, 0, 0, 0};
        uint_fast8_t len = encode_int(FRAGMENT_SIZE, buf);
        ws->send(buf, len, uWS::OpCode::BINARY);
        conn.state = READY;
        break;
    }
    case READY: // Start of message; header comes first
    {
        char header = message[0];
        uint8_t code = header >> 3;
        switch (code) {
        case 0: {
            handleReadyStateMessage(ws, message, length, opCode, conn,
                                    on_rcv_fn, false);
            break;
        }
        case 1: {
            handleReadyStateMessage(ws, message, length, opCode, conn,
                                    on_rcv_fn, true);
            break;
        }
        case 16: {
            cout << "Got ping." << endl;
            this->sendPong(ws);
            break;
        }
        case 17: {
            cout << "Got pong." << endl;
            break;
        }
        }
        conn.state = MSG_IN_FLIGHT;
        break;
    }
    case MSG_IN_FLIGHT: // Got header; collecting fragments
    {
        conn.addFragment(string(message, length));
        if(conn.areAllFragmentsRcvd()) {
            string msg = conn.assembleFragments();
            on_rcv_fn(*this, conn.conn_id, msg.data(), msg.size());
            conn.state = READY;
        }
        break;
    }
    }
}

void TubeServer::handleReadyStateMessage(ws_t *ws, char *message, size_t length,
                                         uWS::OpCode opCode, Connection& conn,
                                         on_rcv_fn_t on_rcv_fn,
                                         bool is_cur_msg_compressed) {
    conn.is_cur_msg_compressed = is_cur_msg_compressed;
    uint32_t num_bytes_consumed;
    conn.setNumFragmentsExpected(message, &num_bytes_consumed);
    conn.state = MSG_IN_FLIGHT;
    if(length > num_bytes_consumed) {
        // Handle remaining bytes
        onMessage(ws, message + num_bytes_consumed,
                  length - num_bytes_consumed, opCode, on_rcv_fn);
    }
}

void TubeServer::close_conn(conn_id_t conn_id) {
    ws_t *ws = this->conn_id_to_ws.at(conn_id);
    string reason("Explicit close");
    ws->close(1000, reason.data(), reason.size());
}

typedef uint_fast8_t compression_id_t;

string make_header(compression_id_t compression_id, int32_t num_fragments) {
    char first_byte = compression_id << 3;
    if(num_fragments <= 7) {
        first_byte |= num_fragments;
        return string(1, first_byte);
    } else {
        char buf[6] = { 0, 0, 0, 0, 0, 0 };
        uint_fast8_t len = encode_int(num_fragments, buf);
        string encoded_num_frags(buf, len);
        string header(1, first_byte);
        return header + encoded_num_frags;
    }
}

typedef pair<string, compression_id_t> pc_return_t;

pc_return_t possibly_compress(string& input, CompressionType compression_type) {
    switch (compression_type) {
    case NONE: {
        return make_pair(input, 0);
    }
    case SMART: {
        // TODO: investigate this size threshold
        if(input.size() <= 15) {
            return make_pair(input, 0);
        } else {
            string compressed = compress(input);
            if(input.size() <= compressed.size()) {
                return make_pair(input, 0);
            } else {
                return make_pair(compressed, 1);
            }
        }
    }
    case DEFLATE: {
        return make_pair(compress(input), 1);
    }
    }
}

typedef pair<const char*, size_t> fragment_t;
typedef vector<fragment_t> fragments_t;

void TubeServer::send(conn_id_t conn_id, const char *msg_data, uint32_t len) {
    ws_t *ws = this->conn_id_to_ws.at(conn_id);
    Connection& conn = this->ws_to_conn.at(ws);
    string msg_string(msg_data, len);
    pc_return_t ret = possibly_compress(msg_string, compression_type);
    string& send_string = ret.first;
    compression_id_t compression_id = ret.second;
    const char *send_data = send_string.data();
    size_t send_data_len = send_string.size();
    // Leave room for header
    size_t fragment_size = conn.peer_fragment_size - 6;
    size_t offset = 0;
    fragments_t fragments;
    while(offset < send_data_len) {
        const char* frag_ptr = send_data + offset;
        offset += fragment_size;
        size_t frag_len = (offset < send_data_len) ?
            fragment_size : send_data_len - offset + fragment_size;
        fragments.push_back(make_pair(frag_ptr, frag_len));
    }
    string header = make_header(compression_id, fragments.size());
    fragment_t first_frag = fragments[0];
    string new_first_frag_str = header + string(first_frag.first,
                                                first_frag.second);
    fragments[0] = make_pair(new_first_frag_str.data(),
                             new_first_frag_str.size());
    for (auto const& fragment : fragments) {
        ws->send(fragment.first, fragment.second, uWS::OpCode::BINARY);
    }
}
