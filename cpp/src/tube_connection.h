#ifndef TUBE_CONNECTION_H
#define TUBE_CONNECTION_H

#include "server_wss.hpp"
#include "utils.h"
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#define FRAGMENT_SIZE 32000

enum State {
    CONNECTED,
    READY,
    MSG_IN_FLIGHT
};

typedef uint_fast8_t compression_id_t;

class TubeServer;

class TubeConnection
{
private:
    TubeServer& ts;
    conn_id_t conn_id;
    std::shared_ptr<WssServer::Connection> connection;
    //std::shared_ptr<WssServer::SendStream> send_stream;
    on_rcv_fn_t& on_rcv_fn;
    State state;
    CompressionType compression_type;
    int32_t peer_fragment_size;
    std::vector<std::string> fragment_buffer;
    int32_t num_fragments_expected;
    bool is_cur_msg_compressed;

    void sendRaw(const std::string& data);
    void sendControlCode(uint_fast8_t code) {
        sendRaw(std::string(1, code << 3));
    }
    void sendPing() { sendControlCode(16); }
    void sendPong() { sendControlCode(17); }
    void handleReadyStateMessage(const std::string& msg);
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

    void addFragment(const std::string& fragment) {
        fragment_buffer.push_back(fragment);
    }

    bool areAllFragmentsRcvd() {
        return fragment_buffer.size() == num_fragments_expected;
    }

    void setNumFragmentsExpected(const std::string& data,
                                 uint32_t *num_bytes_consumed) {
        int_fast8_t n = data[0] & 0x07;
        if(n) {
            num_fragments_expected = n;
            *num_bytes_consumed = 1;
        } else {
            num_fragments_expected = decode_int(
                data.substr(1, std::string::npos), num_bytes_consumed);
            *num_bytes_consumed += 1; // count header byte
        }
    }

public:
TubeConnection(TubeServer& ts, conn_id_t conn_id,
               std::shared_ptr<WssServer::Connection> connection,
               CompressionType compression_type,
               on_rcv_fn_t& on_rcv_fn)
    : ts(ts), conn_id(conn_id), connection(connection),
        compression_type(compression_type), on_rcv_fn(on_rcv_fn) {
        state = CONNECTED;
        peer_fragment_size = 0;
        num_fragments_expected = 0;
        is_cur_msg_compressed = false;
    }

    void handleOnRcv(const std::string& message);

    void send(const std::string& msg_string);

    void close(int code) {
        connection->send_close(code);
    }
};

inline
void TubeConnection::handleOnRcv(const std::string& message) {
    std::cout << "@@@ Entering handleOnRcv. state: " << state << std::endl;
    switch (state) {
    case CONNECTED: // fragment size request
    {
        uint32_t num_bytes_consumed;
        peer_fragment_size = decode_int(message, &num_bytes_consumed);
        char buf[4] = {0, 0, 0, 0};
        uint_fast8_t len = encode_int(FRAGMENT_SIZE, buf);
        sendRaw(std::string(buf, len));
        state = READY;
        break;
    }
    case READY: // Start of message; header comes first
    {
        char header = message[0];
        uint8_t code = header >> 3;
        switch (code) {
        case 0: {
            this->is_cur_msg_compressed = false;
            handleReadyStateMessage(message);
            break;
        }
        case 1: {
            this->is_cur_msg_compressed = true;
            handleReadyStateMessage(message);
            break;
        }
        case 16: {
            std::cout << "Got ping." << std::endl;
            sendPong();
            // TODO: Handle extra data
            break;
        }
        case 17: {
            std::cout << "Got pong." << std::endl;
            // TODO: Handle extra data
            break;
        }
        }
        state = MSG_IN_FLIGHT;
        break;
    }
    case MSG_IN_FLIGHT: // Got header; collecting fragments
    {
        addFragment(message);
        if(areAllFragmentsRcvd()) {
            std::string msg = assembleFragments();
            on_rcv_fn(ts, conn_id, msg);
            state = READY;
        }
        break;
    }
    }
}

inline
void TubeConnection::handleReadyStateMessage(const std::string& msg) {
    uint32_t num_bytes_consumed;
    setNumFragmentsExpected(msg, &num_bytes_consumed);
    state = MSG_IN_FLIGHT;
    if(msg.size() > num_bytes_consumed) {
        // Handle remaining bytes
        handleOnRcv(msg.substr(num_bytes_consumed, std::string::npos));
    }
}

inline
void TubeConnection::sendRaw(const std::string& data) {
    std::cout << "@@ Entering sendRaw. data: " << data << std::endl;
    // TODO: Can we optimize this so it's only created once?
    auto send_stream = std::make_shared<WssServer::SendStream>();
    *send_stream << data;
    connection->send(send_stream, [](const SimpleWeb::error_code &ec) {
            if(ec) {
                std::cout << "Server: Error sending message. ";
                std::cout << "Error: " << ec << ", error message: ";
                std::cout << ec.message() << std::endl;
            }
        });
    std::cout << "@@ Exiting sendRaw. data: " << data << std::endl;
}

inline
std::string make_header(compression_id_t compression_id,
                        int32_t num_fragments) {
    char first_byte = compression_id << 3;
    if(num_fragments <= 7) {
        first_byte |= num_fragments;
        return std::string(1, first_byte);
    } else {
        char buf[6] = { 0, 0, 0, 0, 0, 0 };
        uint_fast8_t len = encode_int(num_fragments, buf);
        std::string encoded_num_frags(buf, len);
        std::string header(1, first_byte);
        return header + encoded_num_frags;
    }
}

typedef std::pair<std::string, compression_id_t> pc_return_t;

inline
pc_return_t possibly_compress(const std::string& input,
                              CompressionType compression_type) {
    switch (compression_type) {
    case NONE: {
        return make_pair(input, 0);
    }
    case SMART: {
        // TODO: investigate this size threshold
        if(input.size() <= 15) {
            return make_pair(input, 0);
        } else {
            std::string compressed = compress(input);
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

inline
void TubeConnection::send(const std::string& msg_string) {
    pc_return_t ret = possibly_compress(msg_string, compression_type);
    std::string& send_string = ret.first;
    compression_id_t compression_id = ret.second;
    // Leave room for header
    size_t fragment_size = peer_fragment_size - 6;
    for (int i=0; i < send_string.size(); i += fragment_size) {
        sendRaw(send_string.substr(i, fragment_size));
    }
}

#endif // TUBE_CONNECTION_H
