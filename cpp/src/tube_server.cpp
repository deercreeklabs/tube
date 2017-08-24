#include "connection.h"
#include "tube_server.h"
#include "utils.h"
#include <cstdint>
#include <iostream>
#include <string>

#define FRAGMENT_SIZE 200000

using namespace std;


TubeServer::TubeServer(const char *sslKey, const char *sslCert, uint32_t port,
                       on_rcv_fn_t on_rcv_fn,
                       on_connect_fn_t on_connect_fn,
                       on_disconnect_fn_t on_disconnect_fn) {
    hub.onConnection(
        [this, on_connect_fn]
        (uWS::WebSocket<uWS::SERVER> *ws, uWS::HttpRequest req) {
            Connection conn;
            this->conns.insert({ws, conn});
            cout << "onConnection:\n  connection_count: ";
            cout << getConnCount() << endl;
            on_connect_fn(ws);
        });
    hub.onDisconnection(
        [this, on_disconnect_fn]
        (uWS::WebSocket<uWS::SERVER> *ws, uint32_t code,
         char *message, size_t length) {
            this->conns.erase(ws);
            string msg(message, length);
            cout << "onDisconnection. Close code: " << code;
            cout << " msg: " << msg << endl;
            on_disconnect_fn(ws, msg.c_str());
        });
    hub.onMessage(
        [this, on_rcv_fn]
        (uWS::WebSocket<uWS::SERVER> *ws, char *message, size_t length,
         uWS::OpCode opCode) {
            if(opCode != uWS::OpCode::BINARY) {
                throw invalid_argument("Got non-binary message on websocket.");
            }
            Connection& conn = this->conns.at(ws);
            switch (conn.state) {
            case CONNECTED: // fragment size request
            {
                uint32_t num_bytes_consumed;
                conn.peer_fragment_size = decode_int(message,
                                                     &num_bytes_consumed);
                cout << "PFSR: " << conn.peer_fragment_size << endl;
                char buf[4] = {0, 0, 0, 0};
                uint_fast8_t len = encode_int(FRAGMENT_SIZE, buf);
                ws->send(buf, len, uWS::OpCode::BINARY);
                conn.state = READY;
                break;
            }
            case READY:
            {
                char header = message[0];
                uint8_t code = header >> 3;
                cout << "code: " << code << endl;
                switch (code) {
                case 0:
                    conn.is_cur_msg_compressed = false;
                    uint32_t num_bytes_consumed;
                    conn.setNumFragments(message, &num_bytes_consumed);
                    break;
                case 1:
                    conn.is_cur_msg_compressed = true;

                    break;
                case 16:
                    cout << "Got ping." << endl;
                    this->sendPong(ws);
                    break;
                case 17:
                    cout << "Got pong." << endl;
                    break;
                }
                on_rcv_fn(ws, message, length);
                break;
            }
            case MSG_IN_FLIGHT:
            {
                break;
            }
            }

        });
    hub.listen(port);
    hub.run();
}

void TubeServer::sendControlCode(ws_t *ws, uint_fast8_t code) {
    char buf = code << 3;
    ws->send(&buf, 1, uWS::OpCode::BINARY);
}
