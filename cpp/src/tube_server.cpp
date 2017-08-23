#include "connection.h"
#include "tube_server.h"
#include <cstdint>
#include <iostream>
#include <string>

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
            switch (this->conns.at(ws).getState()) {
            case CONNECTED: // fragment size request

                break;
            case FRAG_SIZE_NEGOTIATED:

                break;
            case READY:
                on_rcv_fn(ws, (void*)message, length);
                break;
            }

        });
    hub.listen(port);
    hub.run();
}

void TubeServer::send(ws_t *ws, void *data, uint32_t length) {
    ws->send((char*)data, length, uWS::OpCode::BINARY);
};
